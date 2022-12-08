/*
 * Copyright (C) 2017-2022 HERE Europe B.V.
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

package com.here.xyz.hub.rest;

import com.here.xyz.events.ChangesetEvent;
import com.here.xyz.events.ChangesetEvent.Operation;
import com.here.xyz.events.PropertyQuery;
import com.here.xyz.events.PropertyQuery.QueryOperation;
import com.here.xyz.hub.rest.ApiParam.Path;
import static com.here.xyz.hub.rest.ApiParam.Query.getPropertyQuery;
import com.here.xyz.hub.task.SpaceConnectorBasedHandler;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;

public class ChangesetApi extends SpaceBasedApi {

  public ChangesetApi(RouterBuilder rb) {
    rb.operation("deleteChangesets").handler(this::deleteChangesets);
  }

  /**
   * Delete changesets by version number
   */
  private void deleteChangesets(final RoutingContext context) {
    final String space = context.pathParam(Path.SPACE_ID);
    final PropertyQuery version = getPropertyQuery(context.request().query(), "version", false);
    final QueryOperation supportedOp = QueryOperation.LESS_THAN;

    Future<PropertyQuery> future = version != null
        ? Future.succeededFuture(version)
        : Future.failedFuture(new HttpException(HttpResponseStatus.BAD_REQUEST, "Query parameter version is required"));

    future
        .map(v -> supportedOp.equals(v.getOperation())
            ? Future.succeededFuture()
            : Future.failedFuture(new HttpException(HttpResponseStatus.BAD_REQUEST, "Unsupported operator used in the field version")))
        .flatMap(nothing -> SpaceConnectorBasedHandler.execute(context, new ChangesetEvent()
          .withSpace(space)
          .withHistoryVersion(version)
          .withOperation(Operation.DELETE)))
        .onSuccess(result -> this.sendResponse(context, HttpResponseStatus.NO_CONTENT, null))
        .onFailure((ex) -> this.sendErrorResponse(context, ex));
  }
}
