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

package com.here.xyz.jobs.steps.impl.transport;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.here.xyz.Typed;
import com.here.xyz.XyzSerializable;
import com.here.xyz.events.ContextAwareEvent.SpaceContext;
import com.here.xyz.jobs.JobClientInfo;
import com.here.xyz.jobs.steps.Step;
import com.here.xyz.jobs.steps.execution.LambdaBasedStep.LambdaStepRequest.ProcessUpdate;
import com.here.xyz.jobs.steps.execution.StepException;
import com.here.xyz.jobs.steps.impl.SpaceBasedStep;
import com.here.xyz.jobs.steps.impl.tools.ResourceAndTimeCalculator;
import com.here.xyz.jobs.steps.resources.IOResource;
import com.here.xyz.jobs.steps.resources.Load;
import com.here.xyz.jobs.steps.resources.TooManyResourcesClaimed;
import com.here.xyz.models.hub.Ref;
import com.here.xyz.psql.query.QueryBuilder.QueryBuildingException;
import com.here.xyz.responses.StatisticsResponse;
import com.here.xyz.util.db.SQLQuery;
import com.here.xyz.util.service.BaseHttpServerVerticle.ValidationException;
import com.here.xyz.util.web.XyzWebClient;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static com.here.xyz.jobs.steps.execution.db.Database.DatabaseRole.WRITER;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.Phase.JOB_EXECUTOR;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.Phase.STEP_EXECUTE;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.Phase.STEP_ON_ASYNC_UPDATE;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.createQueryContext;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.getTemporaryJobTableName;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.infoLog;
import static com.here.xyz.util.web.XyzWebClient.WebClientException;


/**
 * TaskedSpaceBasedStep is an abstract step that represents a space-based task execution
 * with support for parallelization.
 * This class is responsible for managing task creation, execution, and asynchronous updates
 * in a distributed processing environment.
 *
 * <p>It provides mechanisms to:
 * <ul>
 *   <li>Determine the initial thread count for parallel execution.</li>
 *   <li>Create and manage task items in a database-backed task table.</li>
 *   <li>Build SQL queries dynamically for task execution.</li>
 *   <li>Handle asynchronous task updates and progress tracking.</li>
 *   <li>Manage resource allocation and compute unit estimations.</li>
 *   <li>Resolve version references for space-based processing.</li>
 * </ul>
 *
 * <p>Subclasses must implement methods to define specific task execution logic,
 * including query construction and task item creation.</p>
 *
 * @param <T> The specific subclass type extending this step.
 */
public abstract class TaskedSpaceBasedStep<T extends TaskedSpaceBasedStep>
        extends SpaceBasedStep<T> {
  //Defines how many features a source layer need to have to start parallelization.
  public static final int PARALLELIZTATION_MIN_THRESHOLD = 200_000;
  //Defines how many export threads are getting used
  public static final int PARALLELIZTATION_THREAD_COUNT = 8;

  @JsonView({Internal.class, Static.class})
  protected double overallNeededAcus = -1;

  @JsonView({Internal.class, Static.class})
  protected int calculatedThreadCount = -1;
  @JsonView({Internal.class, Static.class})
  protected int taskItemCount = -1;
  @JsonView({Internal.class, Static.class})
  protected SpaceContext context;
  @JsonView({Internal.class, Static.class})
  protected Ref versionRef;

  public Ref getVersionRef() {
    return versionRef;
  }

  public void setVersionRef(Ref versionRef) {
    this.versionRef = versionRef;
  }

  public SpaceContext getContext() {
    return this.context;
  }

  public void setContext(SpaceContext context) {
    this.context = context;
  }

  /**
   * Sets the initial thread count, which is getting used to start
   * initially threadCount * Tasks.
   *
   * @param schema The database schema to use for determining the initial thread count.
   * @return The initial thread count.
   * @throws WebClientException If an error occurs while interacting with the web client.
   * @throws SQLException If an error occurs while executing SQL queries.
   * @throws TooManyResourcesClaimed If too many resources are claimed during the process.
   */
  protected abstract int setInitialThreadCount(String schema)
          throws WebClientException, SQLException, TooManyResourcesClaimed;

  /**
   * Creates generic task items in the taskAndStatistic table.
   * {@code generateTaskDataObject} is used to generate the task data for each thread.
   *
   * @param schema The database schema to use for the task items.
   * @return The number of task items created.
   * @throws WebClientException If an error occurs while interacting with the web client.
   * @throws SQLException If an error occurs while executing SQL queries.
   * @throws TooManyResourcesClaimed If too many resources are claimed during the process.
   * @throws QueryBuildingException If an error occurs while building the SQL query.
   */
  protected abstract int createTaskItems(String schema)
          throws WebClientException, SQLException, TooManyResourcesClaimed, QueryBuildingException;

  /**
   * Builds a SQL query for a specific task based on the provided schema, task ID, and task data.
   * The implementor needs to invoke the lambda with an ProcessUpdate<SpaceBasedTaskUpdate> payload.
   * Look into transport.sql : report_task_progress()
   *
   * @param schema The database schema to use for the task query.
   * @param taskId The ID of the task for which the query is being built.
   * @param taskData The data associated with the task.
   * @return The constructed SQL query.
   * @throws QueryBuildingException If an error occurs while building the SQL query.
   * @throws TooManyResourcesClaimed If too many resources are claimed during the process.
   * @throws WebClientException If an error occurs while interacting with the web client.
   */
  protected abstract SQLQuery buildTaskQuery(String schema, Integer taskId, TaskData taskData)
          throws QueryBuildingException, TooManyResourcesClaimed, WebClientException;

  @Override
  public List<Load> getNeededResources() {
    return calculateLoadAndSetOverallNeededAcus(context);
  }

  /**
   * Calculates the load and sets the overall needed ACUs (Amazon Compute Units) for the
   * given space context.
   *
   * @param spaceContext The context of the space for which the load is being calculated.
   * @return A list of loads required for the given space context.
   */
  private List<Load> calculateLoadAndSetOverallNeededAcus(SpaceContext spaceContext) {
    try {
      StatisticsResponse statistics = spaceStatistics(spaceContext, true);
      overallNeededAcus = overallNeededAcus != -1 ?
              overallNeededAcus : ResourceAndTimeCalculator.getInstance().calculateNeededExportAcus(statistics.getDataSize().getValue());

      infoLog(JOB_EXECUTOR, this,"Calculated ACUS: byteSize of layer: "
              + statistics.getDataSize().getValue() + " => neededACUs:" + overallNeededAcus);

      return List.of(
              new Load().withResource(dbReader()).withEstimatedVirtualUnits(overallNeededAcus),
              new Load().withResource(IOResource.getInstance()).withEstimatedVirtualUnits(getUncompressedUploadBytesEstimation()));
    }catch (Exception e){
      throw new RuntimeException(e);
    }
  }

  /**
   * Prepares the process by resolving the version reference to an actual version.
   *
   * @param owner The owner of the job.
   * @param ownerAuth The authentication information of the job owner.
   * @throws ValidationException If the version reference is null or cannot be resolved.
   */
  @Override
  public void prepare(String owner, JobClientInfo ownerAuth) throws ValidationException {
    if (versionRef == null)
      throw new ValidationException("Version ref is required.");

    try {
      if (versionRef.isTag()) {
        versionRef = new Ref(resolveTag(versionRef.getTag()));
      } else if (versionRef.isHead()) {
        versionRef = new Ref(resolveHead());
      }
    } catch (StepException e) {
      throw new ValidationException("Unable to resolve the provided version \"" + versionRef + "\". " + e.getMessage());
    }
  }

  private long resolveTag(String tag) throws ValidationException {
    try {
      return loadTag(getSpaceId(), tag).getVersion();
    } catch (WebClientException e) {
      throw handleWebClientException(e, "Unable to resolve tag \"" + tag + "\" of " );
    }
  }

  private long resolveHead() throws ValidationException {
    try {
      return spaceStatistics(context, true).getMaxVersion().getValue();
    } catch (WebClientException e) {
      throw handleWebClientException(e, "Unable to resolve HEAD version of ");
    }
  }

  private ValidationException handleWebClientException(WebClientException e, String message) throws ValidationException {
    if (e instanceof XyzWebClient.ErrorResponseException err && err.getStatusCode() == 428)
      throw new ValidationException(getSpaceId() + " is deactivated!", e);
    throw new ValidationException(message, e);
  }

  /**
   * Starts the initial tasks for the process based on the calculated thread count.
   *
   * @param schema The database schema to use for the export.
   * @throws TooManyResourcesClaimed If too many resources are claimed during the process.
   * @throws QueryBuildingException If an error occurs while building the SQL query.
   * @throws WebClientException If an error occurs while interacting with the web client.
   * @throws SQLException If an error occurs while executing SQL queries.
   */
  protected void startInitialTasks(String schema) throws TooManyResourcesClaimed,
          QueryBuildingException, WebClientException, SQLException {
    for (int i = 0; i < calculatedThreadCount; i++) {
      TaskProgress taskProgressAndTaskItem = getTaskProgressAndTaskItem();
      if(taskProgressAndTaskItem.taskId() == -1)
        break;
      startTask(schema, taskProgressAndTaskItem);
    }
  }

  /**
   * Starts a task for a given schema and taskItem. If the task is finished an invocation with
   * a ProcessUpdate<SpaceBasedTaskUpdate> get send from the database.
   *
   * @param schema The database schema to use for the export.
   * @param taskProgressAndItem The task progress and item details.
   * @throws TooManyResourcesClaimed If too many resources are claimed during the process.
   * @throws QueryBuildingException If an error occurs while building the SQL query.
   * @throws WebClientException If an error occurs while interacting with the web client.
   * @throws SQLException If an error occurs while executing SQL queries.
   */
  protected void startTask(String schema, TaskProgress taskProgressAndItem) throws TooManyResourcesClaimed,
          QueryBuildingException, WebClientException, SQLException {

    if(taskProgressAndItem.taskId() != -1) {
      infoLog(STEP_EXECUTE, this, "Start export with taskId: " + taskProgressAndItem.taskId());
      runReadQueryAsync(buildTaskQuery(schema, taskProgressAndItem.taskId, taskProgressAndItem.taskInput),
              dbReader(), overallNeededAcus / taskItemCount, false);
    }
  }

  @Override
  public void execute(boolean resume) throws Exception {
    String schema = getSchema(db());
    if (!resume) {
      calculatedThreadCount = setInitialThreadCount(schema);
      //create progress update table
      runWriteQuerySync(buildTaskAndStatisticTableStatement(schema, this), db(WRITER), 0);
      taskItemCount = createTaskItems(schema);
    }
    startInitialTasks(schema);
  }

  /**
   * Handles asynchronous updates during the export process. The invocation happens from the
   * database if a task is finished. If unstarted task are present the next one gets started.
   *
   * @param processUpdate The process update containing information about the export progress.
   * @return {@code true} if all tasks are complete, {@code false} otherwise.
   * @throws RuntimeException If an unexpected error occurs during the update process.
   */
  @Override
  protected boolean onAsyncUpdate(ProcessUpdate processUpdate){
    try {
      //Update the task table and mark item as finalized
      SpaceBasedTaskUpdate update = (SpaceBasedTaskUpdate) processUpdate;
      updateTaskAndStatisticTable(update);

      infoLog(STEP_ON_ASYNC_UPDATE, this,"received progress update from: "
              + ((SpaceBasedTaskUpdate) processUpdate).taskId);
      TaskProgress taskProgressAndItem = getTaskProgressAndTaskItem();
      if (taskProgressAndItem.isComplete())
        return true;
      else {
        //If we are not finished, start the next export task
        startTask(getSchema(db()), taskProgressAndItem);
        //Calculate progress and set it on the step's status
        getStatus().setEstimatedProgress((float) taskProgressAndItem.finalizedTasks() / (float) taskProgressAndItem.totalTasks);
      }

      return false;
    }catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Retrieves the task progress and a unstarted task item (if present) from the database.
   *
   * @return The task progress and task item details.
   * @throws WebClientException If an error occurs while interacting with the web client.
   * @throws SQLException If an error occurs while executing SQL queries.
   * @throws TooManyResourcesClaimed If too many resources are claimed during the process.
   */
  private TaskProgress getTaskProgressAndTaskItem() throws WebClientException, SQLException, TooManyResourcesClaimed {
    return runReadQuerySync(retrieveTaskItemAndStatistics(getSchema(db(WRITER))), db(WRITER), 0,
      rs -> {
        if(!rs.next())
          return null;
        try {
          return new TaskProgress(rs.getInt("total"), rs.getInt("started"), rs.getInt("finalized"),
                  rs.getInt("task_id") , XyzSerializable.deserialize(rs.getString("task_data"), TaskData.class));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Can not deserialize task_data!",e);
        }
      });
  }

  private static SQLQuery buildTaskAndStatisticTableStatement(String schema, Step step) {
    return new SQLQuery("""          
            CREATE TABLE IF NOT EXISTS ${schema}.${table}
            (
            	task_id SERIAL,
            	task_data JSONB,
            	bytes_uploaded BIGINT DEFAULT 0,
            	rows_uploaded BIGINT DEFAULT 0,
            	files_uploaded INT DEFAULT 0,
            	started BOOLEAN DEFAULT false,
            	finalized BOOLEAN DEFAULT false,
            	CONSTRAINT ${primaryKey} PRIMARY KEY (task_id)
            );
        """)
            //TODO: CHECK CONSTRAINT!!
            .withVariable("table", getTemporaryJobTableName(step.getId()))
            .withVariable("schema", schema)
            .withVariable("primaryKey", getTemporaryJobTableName(step.getId()) + "_primKey");
  }

  private void updateTaskAndStatisticTable(SpaceBasedTaskUpdate update) throws WebClientException, SQLException, TooManyResourcesClaimed {
    infoLog(STEP_ON_ASYNC_UPDATE, this, "Update process table for taskId: " + update.taskId + " with " + update.featureCount + " features");
    /** create update process table */
    runWriteQuerySync(
            updateTaskItemInTaskAndStatisticTable(getSchema(db(WRITER)), this, update.taskId,
                    update.byteCount, update.featureCount, update.fileCount,  true
            ), db(WRITER), 0);
  }

  private static SQLQuery updateTaskItemInTaskAndStatisticTable(String schema, Step step, int taskId,
                                                                  long bytesUploaded, long rowsUploaded,
                                                                  int filesUploaded, boolean finalized) {
    return new SQLQuery("""             
            UPDATE ${schema}.${table} t
                SET bytes_uploaded = t.bytes_uploaded + #{bytesUploaded},
                    rows_uploaded = t.rows_uploaded + #{rowsUploaded},
                    files_uploaded = t.files_uploaded + #{filesUploaded},
                    finalized = #{finalized}
                WHERE task_id = #{taskId};
        """)
            .withVariable("schema", schema)
            .withVariable("table", getTemporaryJobTableName(step.getId()))
            .withNamedParameter("taskId", taskId)
            .withNamedParameter("bytesUploaded", bytesUploaded)
            .withNamedParameter("rowsUploaded", rowsUploaded)
            .withNamedParameter("filesUploaded", filesUploaded)
            .withNamedParameter("finalized", finalized); //future prove
  }

  private SQLQuery retrieveTaskItemAndStatistics(String schema) throws WebClientException {
    return new SQLQuery("SELECT total, started, finalized, task_id, task_data from get_task_item_and_statistics();")
            .withContext(getQueryContext(schema));
  }

  protected SQLQuery retrieveStatisticFromTaskAndStatisticTable(String schema) {
    return new SQLQuery("""
          SELECT sum(rows_uploaded) as rows_uploaded,
                 sum(CASE
                     WHEN (bytes_uploaded)::bigint > 0
                     THEN (files_uploaded)::bigint
                     ELSE 0
                 END) as files_uploaded,
                 sum(bytes_uploaded)::bigint as bytes_uploaded
                  FROM ${schema}.${tmpTable};
        """)
            .withVariable("schema", schema)
            .withVariable("tmpTable", getTemporaryJobTableName(getId()));
  }

  protected static SQLQuery insertTaskItemInTaskAndStatisticTable(String schema, Step step, TaskData taskData) {
    /** TODO: switch to complete upsert */
    return new SQLQuery("""             
            INSERT INTO  ${schema}.${table} AS t (task_data)
                VALUES (#{taskData}::JSONB);
        """)
            .withVariable("schema", schema)
            .withVariable("table", getTemporaryJobTableName(step.getId()))
            .withNamedParameter("taskData", taskData.serialize());
  }

  protected Map<String, Object> getQueryContext(String schema) throws WebClientException {
    String superTable = space().getExtension() != null ? getRootTableName(superSpace()) : null;
    return createQueryContext(getId(), schema, getRootTableName(space()), (space().getVersionsToKeep() > 1), superTable);
  }

  protected record TaskProgress(int totalTasks, int startedTasks, int finalizedTasks, Integer taskId, TaskData taskInput) {
    public boolean isComplete() {
      return totalTasks == finalizedTasks;
    }
  }

  public static class SpaceBasedTaskUpdate extends ProcessUpdate<SpaceBasedTaskUpdate> {
    public int taskId;
    public long byteCount;
    public long featureCount;
    public int fileCount;
  }

  @JsonTypeName("TaskData")
  protected record TaskData(Object taskInput) implements Typed{ }
}
