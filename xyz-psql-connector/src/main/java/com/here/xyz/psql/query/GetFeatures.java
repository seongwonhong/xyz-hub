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

package com.here.xyz.psql.query;

import com.here.xyz.connectors.ErrorResponseException;
import com.here.xyz.events.Event;
import com.here.xyz.events.GetFeaturesByIdEvent;
import com.here.xyz.events.QueryEvent;
import com.here.xyz.events.SearchForFeaturesEvent;
import com.here.xyz.models.geojson.implementation.FeatureCollection;
import com.here.xyz.psql.DatabaseHandler;
import com.here.xyz.psql.QueryRunner;
import com.here.xyz.psql.SQLQuery;
import com.here.xyz.psql.SQLQueryBuilder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class GetFeatures<E extends Event> extends QueryRunner<E, FeatureCollection> {

  public GetFeatures(E event, DatabaseHandler dbHandler) throws SQLException, ErrorResponseException {
    super(event, dbHandler);
    setUseReadReplica(true);
  }

  protected SQLQuery buildQuery(E event, String filterWhereClause) {
    SQLQuery query = new SQLQuery(
        "SELECT ${{selection}}, ${{geo}}${{iColumn}}"
            + "    FROM ${schema}.${table}"
            + "    WHERE ${{filterWhereClause}} ${{orderBy}} ${{limit}} ${{offset}}");

    if (event instanceof QueryEvent)
      query.setQueryFragment("selection", buildSelectionFragment((QueryEvent) event));
    else
      query.setQueryFragment("selection", "jsondata");

    query.setQueryFragment("geo", buildGeoFragment(event));
    query.setQueryFragment("iColumn", ""); //NOTE: This can be overridden by implementing subclasses
    query.setQueryFragment("filterWhereClause", filterWhereClause);
    query.setQueryFragment("orderBy", ""); //NOTE: This can be overridden by implementing subclasses
    query.setQueryFragment("limit", ""); //NOTE: This can be overridden by implementing subclasses
    query.setQueryFragment("offset", ""); //NOTE: This can be overridden by implementing subclasses
    query.setVariable(SCHEMA, getSchema());
    query.setVariable(TABLE, dbHandler.getConfig().readTableFromEvent(event));

    return query;
  }

  @Override
  public FeatureCollection handle(ResultSet rs) throws SQLException {
    return dbHandler.defaultFeatureResultSetHandler(rs);
  }

  protected static SQLQuery buildSelectionFragment(QueryEvent event) {
    if (event.getSelection() == null || event.getSelection().size() == 0)
      return new SQLQuery("jsondata");

    List<String> selection = event.getSelection();
    if (!selection.contains("type")) {
      selection = new ArrayList<>(selection);
      selection.add("type");
    }

    return new SQLQuery("(SELECT "
        + "CASE WHEN prj_build ?? 'properties' THEN prj_build "
        + "ELSE jsonb_set(prj_build,'{properties}','{}'::jsonb) "
        + "END "
        + "FROM prj_build(#{selection}, jsondata)) AS jsondata",
        Collections.singletonMap("selection", selection.toArray(new String[0])));
  }

  /**
   * This method is kept for backwards compatibility until refactoring is complete.
   */
  public static SQLQuery buildSelectionFragmentBWC(QueryEvent event) {
    SQLQuery selectionFragment = buildSelectionFragment(event);
    selectionFragment.replaceNamedParameters();
    return selectionFragment;
  }

  protected String buildGeoFragment(E event) {
    boolean isForce2D = false;
    if (event instanceof GetFeaturesByIdEvent)
      isForce2D = ((GetFeaturesByIdEvent) event).isForce2D();
    else if (event instanceof SearchForFeaturesEvent)
      isForce2D = ((SearchForFeaturesEvent<?>) event).isForce2D();

    return "replace(ST_AsGeojson(" + (isForce2D ? "ST_Force2D" : "ST_Force3D") + "(geo), " + SQLQueryBuilder.GEOMETRY_DECIMAL_DIGITS + "), 'nan', '0')";
  }
}
