/*
 * Copyright (C) 2017-2024 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.xyz.jobs.service;

import static com.here.xyz.jobs.RuntimeInfo.State.NOT_READY;
import static com.here.xyz.jobs.RuntimeInfo.State.RUNNING;
import static com.here.xyz.jobs.RuntimeStatus.Action.CANCEL;
import static com.here.xyz.jobs.service.JobApi.ApiParam.Path.JOB_ID;
import static com.here.xyz.jobs.service.JobApi.ApiParam.Path.SPACE_ID;
import static io.netty.handler.codec.http.HttpResponseStatus.ACCEPTED;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CREATED;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.here.xyz.XyzSerializable;
import com.here.xyz.jobs.Job;
import com.here.xyz.jobs.RuntimeStatus;
import com.here.xyz.jobs.datasets.DatasetDescription;
import com.here.xyz.jobs.steps.inputs.Input;
import com.here.xyz.jobs.steps.inputs.InputsFromJob;
import com.here.xyz.jobs.steps.inputs.InputsFromS3;
import com.here.xyz.jobs.steps.inputs.ModelBasedInput;
import com.here.xyz.jobs.steps.inputs.UploadUrl;
import com.here.xyz.jobs.steps.outputs.Output;
import com.here.xyz.util.service.HttpException;
import com.here.xyz.util.service.rest.Api;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JobApi extends Api {
  protected static final Logger logger = LogManager.getLogger();
  protected JobApi() {}

  public JobApi(RouterBuilder rb) {
    rb.getRoute("postJob").setDoValidation(false).addHandler(handleErrors(this::postJob));
    rb.getRoute("getJobs").setDoValidation(false).addHandler(handleErrors(this::getJobs));
    rb.getRoute("getJob").setDoValidation(false).addHandler(handleErrors(this::getJob));
    rb.getRoute("deleteJob").setDoValidation(false).addHandler(handleErrors(this::deleteJob));
    rb.getRoute("postJobInputs").setDoValidation(false).addHandler(handleErrors(this::postJobInput));
    rb.getRoute("getJobInputs").setDoValidation(false).addHandler(handleErrors(this::getJobInputs));
    rb.getRoute("getJobOutputs").setDoValidation(false).addHandler(handleErrors(this::getJobOutputs));
    rb.getRoute("patchJobStatus").setDoValidation(false).addHandler(handleErrors(this::patchJobStatus));
    rb.getRoute("getJobStatus").setDoValidation(false).addHandler(handleErrors(this::getJobStatus));
  }

  protected void postJob(final RoutingContext context) throws HttpException {
    createNewJob(context, getJobFromBody(context));
  }

  protected Future<Job> createNewJob(RoutingContext context, Job job) {
    logger.info(getMarker(context), "Received job creation request: {}", job.serialize(true));
    return job.create().submit()
        .map(res -> job)
        .onSuccess(res -> {
          sendResponse(context, CREATED.code(), res);
          logger.info(getMarker(context), "Job was created successfully: {}", job.serialize(true));
        })
        .onFailure(err -> sendErrorResponse(context, err));
  }

  protected void getJobs(final RoutingContext context) {
    Job.loadAll()
        .onSuccess(res -> sendResponse(context, OK.code(), res))
        .onFailure(err -> sendErrorResponse(context, err));
  }

  protected void getJob(final RoutingContext context) {
    String jobId = ApiParam.getPathParam(context, JOB_ID);
    loadJob(context, jobId)
        .onSuccess(res -> sendResponse(context, OK.code(), res))
        .onFailure(err -> sendErrorResponse(context, err));

  }

  protected void deleteJob(final RoutingContext context) {
    String jobId = ApiParam.getPathParam(context, JOB_ID);
    loadJob(context, jobId)
        .compose(job -> Job.delete(jobId).map(job))
        .onSuccess(res -> sendResponse(context, OK.code(), res))
        .onFailure(err -> sendErrorResponse(context, err));
  }

  protected void postJobInput(final RoutingContext context) throws HttpException {
    String jobId = ApiParam.getPathParam(context, JOB_ID);
    Input input = getJobInputFromBody(context);
    if (input instanceof UploadUrl uploadUrl) {
      loadJob(context, jobId)
          .compose(job -> job.getStatus().getState() == NOT_READY
              ? Future.succeededFuture(job)
              : Future.failedFuture(new HttpException(BAD_REQUEST, "No inputs can be created after a job was submitted.")))
          .map(job -> job.createUploadUrl(uploadUrl.isCompressed()))
          .onSuccess(res -> sendResponse(context, CREATED.code(), res))
          .onFailure(err -> sendErrorResponse(context, err));
    }
    else if (input instanceof InputsFromS3 s3Inputs) {
      loadJob(context, jobId)
          .compose(job -> job.getStatus().getState() == NOT_READY
              ? Future.succeededFuture(job)
              : Future.failedFuture(new HttpException(BAD_REQUEST, "No inputs can be created after a job was submitted.")))
          .compose(job -> {
            s3Inputs.dereference(job.getId());
            return Future.succeededFuture();
          })
          .onSuccess(v -> sendResponse(context, OK.code(), (XyzSerializable) null))
          .onFailure(err -> sendErrorResponse(context, err));
    }
    else if (input instanceof ModelBasedInput modelBasedInput) {
      loadJob(context, jobId)
          .compose(job -> {
            if (!job.isPipeline())
              return Future.failedFuture(new HttpException(BAD_REQUEST, "No inputs other than " + UploadUrl.class.getSimpleName() + "s can be "
                  + "created for this job."));
            else if (job.getStatus().getState() != RUNNING)
              return Future.failedFuture(new HttpException(BAD_REQUEST, "No inputs can be created for this job before it is running."));
            else if (context.request().bytesRead() > 256 * 1024)
              return Future.failedFuture(new HttpException(BAD_REQUEST, "The maximum size of an input for this job is 256KB."));
            else
              return job.consumeInput(modelBasedInput);
          })
          .onSuccess(v -> sendResponse(context, OK.code(), (XyzSerializable) null))
          .onFailure(err -> sendErrorResponse(context, err));
    }
    else if (input instanceof InputsFromJob inputsReference) {
      //NOTE: Both jobs have to be loaded to authorize the user for both
      loadJob(context, jobId)
          .compose(job -> loadJob(context, inputsReference.getJobId()).compose(referencedJob -> {
            try {
              if (!Objects.equals(referencedJob.getOwner(), job.getOwner()))
                return Future.failedFuture(new HttpException(FORBIDDEN, "Inputs of job " + inputsReference.getJobId()
                    + " can not be referenced by job " + job.getId() + " as it has a different owner."));

              inputsReference.dereference(job.getId());
              return Future.succeededFuture();
            }
            catch (IOException e) {
              return Future.failedFuture(e);
            }
          }))
          .onSuccess(v -> sendResponse(context, OK.code(), (XyzSerializable) null))
          .onFailure(err -> sendErrorResponse(context, err));
    }
    else
      throw new NotImplementedException("Input type " + input.getClass().getSimpleName() + " is not supported.");
  }

  protected void getJobInputs(final RoutingContext context) {
    String jobId = ApiParam.getPathParam(context, JOB_ID);

    loadJob(context, jobId)
        .compose(job -> job.loadInputs())
        .onSuccess(res -> sendResponse(context, OK.code(), res, new TypeReference<List<Input>>() {}))
        .onFailure(err -> sendErrorResponse(context, err));
  }

  protected void getJobOutputs(final RoutingContext context) {
    String jobId = ApiParam.getPathParam(context, JOB_ID);

    loadJob(context, jobId)
        .compose(job -> job.loadOutputs())
        .onSuccess(res -> sendResponse(context, OK.code(), res, new TypeReference<List<Output>>() {}))
        .onFailure(err -> sendErrorResponse(context, err));
  }

  protected void patchJobStatus(final RoutingContext context) throws HttpException {
    String jobId = ApiParam.getPathParam(context, JOB_ID);
    RuntimeStatus status = getStatusFromBody(context);
    loadJob(context, jobId)
        .compose(job -> tryExecuteAction(context, status, job))
        .onSuccess(patchedStatus -> sendResponse(context, ACCEPTED.code(), patchedStatus))
        .onFailure(err -> sendErrorResponse(context, err));
  }

  protected void getJobStatus(final RoutingContext context) {
    String jobId = ApiParam.getPathParam(context, JOB_ID);
    loadJob(context, jobId)
        .onSuccess(res -> sendResponse(context, OK.code(), res.getStatus()))
        .onFailure(err -> sendErrorResponse(context, err));
  }

  protected Job getJobFromBody(RoutingContext context) throws HttpException {
    try {
      Job job = XyzSerializable.deserialize(context.body().asString(), Job.class);

      String spaceId = ApiParam.getPathParam(context, SPACE_ID);

      if (spaceId != null && job.getSource() instanceof DatasetDescription.Space space)
        space.setId(spaceId);

      return job;
    }
    catch (JsonProcessingException e) {
      //TODO: Decide if we want to forward the cause to the user.
      //e.g. an invalid versionRef(4,2) will end up here - without any indication for the user at the end
      throw new HttpException(BAD_REQUEST, "Error parsing request", e);
    }
  }

  protected Future<Job> loadJob(RoutingContext context, String jobId) {
    return Job.load(jobId)
        .compose(job -> {
          if (job == null)
            return Future.failedFuture(new HttpException(NOT_FOUND, "The requested job does not exist"));
          return authorizeAccess(context, job).map(job);
        });
  }

  protected Future<Void> authorizeAccess(RoutingContext context, Job job) {
    return Future.succeededFuture();
  }

  protected Input getJobInputFromBody(RoutingContext context) throws HttpException {
    try {
      try {
        return XyzSerializable.deserialize(context.body().asString(), Input.class);
      }
      catch (InvalidTypeIdException e) {
        Map<String, Object> jsonInput = XyzSerializable.deserialize(context.body().asString(), Map.class);
        throw new NotImplementedException("Input type " + jsonInput.get("type") + " is not supported.", e);
      }
    }
    catch (JsonProcessingException e) {
      throw new HttpException(BAD_REQUEST, "Error parsing request", e);
    }
  }

  protected RuntimeStatus getStatusFromBody(RoutingContext context) throws HttpException {
    try {
      return XyzSerializable.deserialize(context.body().asString(), RuntimeStatus.class);
    }
    catch (JsonProcessingException e) {
      throw new HttpException(BAD_REQUEST, "Error parsing request", e);
    }
  }

  protected Future<RuntimeStatus> tryExecuteAction(RoutingContext context, RuntimeStatus status, Job job) {
    job.getStatus().setDesiredAction(status.getDesiredAction());
    return (switch (status.getDesiredAction()) {
      case START -> job.submit();
      case CANCEL -> job.cancel();
      case RESUME -> job.resume();
    })
        .onSuccess(actionExecuted -> {
          if (status.getDesiredAction() != CANCEL || actionExecuted) {
            job.getStatus().setDesiredAction(null);
            job.storeStatus(null);
          }
        })
        .map(res -> job.getStatus());
  }

  public static class ApiParam {

    public static String getPathParam(RoutingContext context, String param) {
      return context.pathParam(param);
    }

    public static String getQueryParam(RoutingContext context, String param) {
      return context.queryParams().get(param);
    }

    public static class Path {
      static final String SPACE_ID = "spaceId";
      static final String JOB_ID = "jobId";
    }

    public static class Query {
      static final String STATE = "state";
      static final String RESOURCE = "resource";
    }
  }
}
