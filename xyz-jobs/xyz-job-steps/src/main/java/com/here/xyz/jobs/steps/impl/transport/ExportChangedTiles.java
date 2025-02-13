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

import com.fasterxml.jackson.annotation.JsonView;
import com.here.xyz.events.ContextAwareEvent.SpaceContext;
import com.here.xyz.jobs.datasets.filters.SpatialFilter;

import static com.here.xyz.jobs.steps.execution.db.Database.DatabaseRole.READER;
import static com.here.xyz.jobs.steps.execution.db.Database.DatabaseRole.WRITER;

import com.here.xyz.jobs.steps.StepExecution;
import com.here.xyz.jobs.steps.outputs.TileInvalidations;
import com.here.xyz.jobs.steps.resources.TooManyResourcesClaimed;
import com.here.xyz.models.geojson.HQuad;
import com.here.xyz.models.geojson.WebMercatorTile;
import com.here.xyz.models.geojson.coordinates.BBox;
import com.here.xyz.models.hub.Ref;
import com.here.xyz.psql.query.GetFeaturesByGeometryBuilder;
import com.here.xyz.psql.query.GetFeaturesByGeometryBuilder.GetFeaturesByGeometryInput;
import com.here.xyz.psql.query.GetFeaturesByIdsBuilder;
import com.here.xyz.psql.query.QueryBuilder.QueryBuildingException;
import com.here.xyz.util.db.SQLQuery;
import com.here.xyz.util.service.BaseHttpServerVerticle.ValidationException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.here.xyz.jobs.steps.Step.Visibility.SYSTEM;
import static com.here.xyz.jobs.steps.Step.Visibility.USER;
import static com.here.xyz.events.ContextAwareEvent.SpaceContext.DEFAULT;
import static com.here.xyz.events.ContextAwareEvent.SpaceContext.EXTENSION;
import static com.here.xyz.events.ContextAwareEvent.SpaceContext.SUPER;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.Phase.STEP_ON_ASYNC_SUCCESS;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.getTemporaryJobTableName;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.infoLog;
import static com.here.xyz.jobs.steps.impl.transport.TransportTools.Phase.STEP_EXECUTE;
import static com.here.xyz.util.web.XyzWebClient.WebClientException;


/**
 * The {@code ExportChangedTiles} class extends {@link ExportSpaceToFiles} to specifically handle the
 * export of changed tiles from a given space. It determines the affected tiles based on changes
 * detected between different versions of spatial data.
 *
 * <p>Key features of this class include:
 * <ul>
 *   <li>Identifies affected tiles by analyzing spatial data changes across versions.</li>
 *   <li>Supports configurable target tile levels for export granularity.</li>
 *   <li>Handles tile representation using different quad types (HERE_QUAD, MERCATOR_QUAD).</li>
 *   <li>Ensures data consistency by validating version history and spatial constraints.</li>
 *   <li>Manages parallel execution for efficient processing of tile exports.</li>
 * </ul>
 * </p>
 *
 * <p>This step produces outputs of type {@link FeatureStatistics}, {@link DownloadUrl},
 * and {@link TileInvalidations}.</p>
 */
public class ExportChangedTiles extends ExportSpaceToFiles {
  public static final String TILE_INVALIDATIONS = "tileInvalidations";

  @JsonView({Internal.class, Static.class})
  private int targetLevel = 11;

  @JsonView({Internal.class, Static.class})
  private QuadType quadType = QuadType.HERE_QUAD;

  public int getTargetLevel() {
    return targetLevel;
  }

  public void setTargetLevel(int targetLevel) {
    this.targetLevel = targetLevel;
  }

  public ExportChangedTiles withTargetLevel(int targetLevel) {
    setTargetLevel(targetLevel);
    return this;
  }

  public QuadType getQuadType() {
    return quadType;
  }

  public void setQuadType(QuadType quadType) {
    this.quadType = quadType;
  }

  public ExportChangedTiles withQuadType(QuadType quadType) {
    setQuadType(quadType);
    return this;
  }

  public enum QuadType {
    HERE_QUAD,
    MERCATOR_QUAD
  }

  {
    setOutputSets(List.of(
        new OutputSet(STATISTICS, USER, true),
        new OutputSet(INTERNAL_STATISTICS, SYSTEM, true),
        new OutputSet(EXPORTED_DATA, USER, false),
        new OutputSet(TILE_INVALIDATIONS, USER, true)
    ));
  }

  @Override
  public boolean isEquivalentTo(StepExecution other) {
    if (!(other instanceof ExportChangedTiles otherExport))
      return super.isEquivalentTo(other);

    try {
      //Deduplicate code
      return Objects.equals(otherExport.getSpaceId(), getSpaceId())
              && Objects.equals(otherExport.versionRef, versionRef)
              && (otherExport.context == context || (space().getExtension() == null && otherExport.context == null && context == SUPER))
              && Objects.equals(otherExport.spatialFilter, spatialFilter)
              && Objects.equals(otherExport.propertyFilter, propertyFilter)
              && Objects.equals(otherExport.targetLevel, targetLevel)
              && Objects.equals(otherExport.quadType, quadType);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

// TODO: Fix and implement
//  @Override
//  public int getEstimatedExecutionSeconds() {
//  }

  @Override
  public String getDescription() {
    return "Export ChangedTiles from space " + getSpaceId();
  }

  @Override
  public boolean validate() throws ValidationException {
      super.validate();
      try {
        //if(space().getExtension() == null)
        //  throw new ValidationException("Only supported for composite compounds!");
        if (space().getVersionsToKeep() <= 1)
          throw new ValidationException("Versions to keep must be greater than 1!");
        if (targetLevel < 0 || targetLevel > 12) {
          throw new ValidationException("TargetLevel must be between 0 and 12!");
        }
      }catch (WebClientException e) {
        throw new ValidationException("Error loading resource " + getSpaceId(), e);
      }
      return true;
  }

  @Override
  protected int setInitialThreadCount(String schema){
    //always use 8 Threads
    return 8;
  }

  @Override
  protected int createTaskItems(String schema) throws TooManyResourcesClaimed,
          QueryBuildingException, WebClientException, SQLException {
    Set<String> affectedTiles = new HashSet<>();
    List<String> changedFeatureIds = new ArrayList<>();

    //Get affected Tiles from Delta in range [version.getStartVersion() + 1 : version.getEndVersion()]
    runReadQuerySync(getAffectedTilesFromDelta(new Ref(versionRef.getStartVersion(),
            versionRef.getEndVersion())), db(), 0, rs -> {
      while (rs.next()){
        String tileID = rs.getString("tile");
        if(tileID != null)
          affectedTiles.add(tileID);
        changedFeatureIds.add(rs.getString("id"));
      }
      return null;
    });
    infoLog(STEP_EXECUTE, this, "Added affected tiles from delta in version range "
            + versionRef +". Intermediate result size: "+ affectedTiles.size());

    //Get affected Tiles from list of Feature in version [versionRef.getStartVersion()]
    runReadQuerySync(getAffectedTilesFromBase(changedFeatureIds, new Ref(versionRef.getStartVersion())),
            db(), 0, rs -> {
      while (rs.next()){
        affectedTiles.add(rs.getString("tile"));
      }
      return null;
    });
    infoLog(STEP_EXECUTE, this, "Added affected tiles from base version "
            + versionRef.getStartVersion() +". Final Result size: "+ affectedTiles.size());

    //Write taskList with all unique tileIds which we need to export
    for(String tileId : affectedTiles){
      infoLog(STEP_EXECUTE, this,"Add initial entry in process_table for thread number: " + tileId );
      runWriteQuerySync(insertTaskItemInTaskAndStatisticTable(schema, this, new TaskData(tileId)), db(WRITER), 0);
    }
    return affectedTiles.size();
  }

  @Override
  protected String generateContentQueryForExportPlugin(TaskData taskData)
          throws WebClientException, TooManyResourcesClaimed, QueryBuildingException {
    //We are exporting now the data from the provided tile ID.
    return getFeaturesByGeometryQuery(
            DEFAULT,
            null, //no override needed - use default
            null, //no spatial Filter is needed - we take Geometry from tile
            taskData.taskInput().toString(), //tileId from task_item
            new Ref(versionRef.getEndVersion()) //export tiles from endVersion
    ).toExecutableQueryString();
  }

  @Override
  protected void onAsyncSuccess() throws Exception {
    generateInvalidationTileListOutput();
    super.onAsyncSuccess();
  }

  private void generateInvalidationTileListOutput() throws WebClientException
          , SQLException, TooManyResourcesClaimed, IOException {

    SQLQuery invalidationListQuery = getInvalidationList(getSchema(db(READER)), getTemporaryJobTableName(getId()));
    TileInvalidations tileList = (TileInvalidations) runReadQuerySync(invalidationListQuery, db(READER),
            0, rs -> rs.next()
                    ? new TileInvalidations()
                          .withTileLevel(targetLevel)
                          .withQuadType(quadType)
                          .withTileIds(Optional.ofNullable(rs.getArray("tile_list"))
                                  .map(array -> {
                                    try {
                                      return (String[]) array.getArray();
                                    } catch (SQLException e) {
                                      throw new RuntimeException("Error retrieving tile_list array from ResultSet", e);
                                    }
                                  })
                                  .map(Arrays::asList)
                                  .orElse(Collections.emptyList()))
                    : new TileInvalidations());

    //Skip if tileList=0 ?
    infoLog(STEP_ON_ASYNC_SUCCESS, this, "Write TILE_INVALIDATIONS output. Size: {}.",
            Integer.toString(tileList.getTileIds().size()));

    registerOutputs(List.of(tileList), TILE_INVALIDATIONS);
  }

  private String getQuadFunctionName(){
    return switch (quadType) {
      case HERE_QUAD -> "here_quad";
      case MERCATOR_QUAD -> "mercator_quad";
    };
  }

  private SQLQuery getAffectedTilesFromBase(List<String> ids, Ref versionRef) throws TooManyResourcesClaimed, QueryBuildingException, WebClientException {
    SQLQuery getBaseFeaturesByIdQuery = generateGetFeaturesByIdsQuery(ids, DEFAULT, versionRef);

    SQLQuery getChangedTilesQuery =  new SQLQuery("SELECT "+getQuadFunctionName()+
            """
            (f.colX, f.rowY, f.level) as tile
               FROM (
                    ${{squashedDeltaChangesQuery}}
                ) a
            CROSS JOIN LATERAL for_geometry(a.geo, #{targetLevel}, #{quadType}) f
        """)
            .withNamedParameter("targetLevel", targetLevel)
            .withNamedParameter("quadType", quadType.name())
            .withQueryFragment("squashedDeltaChangesQuery", getBaseFeaturesByIdQuery);

    return getChangedTilesQuery;
  }

  private SQLQuery getAffectedTilesFromDelta(Ref versionRef)
          throws TooManyResourcesClaimed, QueryBuildingException, WebClientException {
    SQLQuery getDeltaFeaturesQuery = getFeaturesByGeometryQuery(
            EXTENSION,
            new SQLQuery("id, geo"),
            spatialFilter,
            null,
            versionRef
    );
    //We are getting a list tileId,id
    //If feature is deleted we do not have geometry anymore so we
    //in this case a row only have the id. E.g. null,id123
    return new SQLQuery("SELECT "+getQuadFunctionName()+
            """
            (f.colX, f.rowY, f.level) as tile, id
            FROM (
                 ${{squashedDeltaChangesQuery}}
             ) a
            LEFT JOIN LATERAL for_geometry(a.geo, #{targetLevel}, #{quadType}) f ON TRUE;
     """)
            .withNamedParameter("targetLevel", targetLevel)
            .withNamedParameter("quadType", quadType.name())
            .withQueryFragment("squashedDeltaChangesQuery", getDeltaFeaturesQuery);
  }

  private SQLQuery getFeaturesByGeometryQuery(
          SpaceContext context,
          SQLQuery selectClauseOverride,
          SpatialFilter spatialFilter,
          String tileId,
          Ref versionRef
  ) throws WebClientException, TooManyResourcesClaimed, QueryBuildingException {

    GetFeaturesByGeometryBuilder queryBuilder = new GetFeaturesByGeometryBuilder()
            .withDataSourceProvider(requestResource(dbReader(), 0));

    GetFeaturesByGeometryInput input = createGetFeaturesByGeometryInput(context, spatialFilter, versionRef);

    if (tileId != null) {
      return getFeaturesByTileIdQuery(queryBuilder, input, tileId, selectClauseOverride);
    }

    return queryBuilder
            .withSelectClauseOverride(selectClauseOverride)
            .buildQuery(input);
  }

  private SQLQuery getFeaturesByTileIdQuery(GetFeaturesByGeometryBuilder queryBuilder,
                                            GetFeaturesByGeometryInput input,
                                            String tileId,
                                            SQLQuery selectClauseOverride)
          throws QueryBuildingException {

    BBox tileBBOX = switch (quadType) {
      case HERE_QUAD -> new HQuad(tileId, false).getBoundingBox();
      case MERCATOR_QUAD -> WebMercatorTile.forQuadkey(tileId).getExtendedBBox(0);
    };

    SQLQuery contentQuery = queryBuilder
            .withGeoFilterOverride(tileBBOX)  //TODO: unclipped?
            .withSelectClauseOverride(selectClauseOverride)
            .buildQuery(input);

    return buildTileQuery(contentQuery, tileId);
  }

  private SQLQuery getInvalidationList(String schema, String tmpTableName){
    return new SQLQuery("""
          SELECT array_agg(element) AS tile_list
              FROM (
                  SELECT jsonb_array_elements_text(
                          jsonb_build_array(task_data->'taskData')
                  ) AS element
                  FROM ${schema}.${table}
                  WHERE bytes_uploaded = 0
             ) X
        """)
          .withVariable("table", tmpTableName)
          .withVariable("schema", schema);
  }

  private SQLQuery buildTileQuery(SQLQuery contentQuery, String tileId) {
    return new SQLQuery("""
          SELECT geo, jsonb_set(
                    jsondata,
                    '{properties,@ns:com:here:xyz,partitionKey}',
                    #{tileId}
                ) AS jsondata
             FROM (${{contentQuery}}) A
        """)
            .withNamedParameter("tileId", tileId)
            .withQueryFragment("contentQuery", contentQuery);
  }

  private SQLQuery generateGetFeaturesByIdsQuery(List<String> ids, SpaceContext context, Ref versionRef)
          throws WebClientException, TooManyResourcesClaimed, QueryBuildingException {

    GetFeaturesByIdsBuilder queryBuilder = new GetFeaturesByIdsBuilder()
            .withDataSourceProvider(requestResource(dbReader(), 0));
    GetFeaturesByIdsBuilder.GetFeaturesByIdsInput input = createGetFeaturesByIdsInput(ids, context, versionRef);

    return queryBuilder
            .withSelectClauseOverride(new SQLQuery("geo"))
            .buildQuery(input);
  }

  private GetFeaturesByIdsBuilder.GetFeaturesByIdsInput createGetFeaturesByIdsInput(List<String> ids,
                                                                                    SpaceContext context, Ref versionRef)
          throws WebClientException {
    return new GetFeaturesByIdsBuilder.GetFeaturesByIdsInput(
            space().getId(),
            hubWebClient().loadConnector(space().getStorage().getId()).params,
            space().getExtension() != null ? space().resolveCompositeParams(superSpace()) : null,
            context,
            space().getVersionsToKeep(),
            versionRef,
            ids
    );
  }
}
