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

package com.here.xyz.psql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.here.xyz.connectors.AbstractConnectorHandler.TraceItem;
import com.here.xyz.events.ModifyFeaturesEvent;
import com.here.xyz.models.geojson.implementation.Feature;
import com.here.xyz.models.geojson.implementation.FeatureCollection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DatabaseTransactionalWriter extends  DatabaseWriter{

    protected static void deleteFeatures(DatabaseHandler dbh, ModifyFeaturesEvent event, TraceItem traceItem,
                                         List<FeatureCollection.ModificationFailure> fails, Map<String, String> deletes,
                                         Connection connection, Integer version)
            throws SQLException {
        boolean handleUUID = event.getEnableUUID();
        String schema = dbh.config.getDatabaseSettings().getSchema();
        String table = dbh.config.readTableFromEvent(event);

        final PreparedStatement batchDeleteStmt = deleteStmtSQLStatement(connection,schema,table,handleUUID);
        final PreparedStatement batchDeleteStmtWithoutUUID = deleteStmtSQLStatement(connection,schema,table,false);

        /** If versioning is enabled than we are going to perform an update instead of an delete. The trigger will finally delete the row.*/
        final PreparedStatement batchDeleteStmtVersioned =  versionedDeleteStmtSQLStatement(connection,schema,table,handleUUID);
        final PreparedStatement batchDeleteStmtVersionedWithoutUUID =  versionedDeleteStmtSQLStatement(connection,schema,table,false);

        Set<String> idsToDelete = deletes.keySet();

        List<String> deleteIdList = new ArrayList<>();
        List<String> deleteIdListWithoutUUID = new ArrayList<>();

        for (String deleteId : idsToDelete) {
            final String puuid = deletes.get(deleteId);

            if(version == null){
                if(handleUUID && puuid == null){
                    batchDeleteStmtWithoutUUID.setString(1, deleteId);
                    batchDeleteStmtWithoutUUID.addBatch();
                    deleteIdListWithoutUUID.add(deleteId);
                }
                else {
                    batchDeleteStmt.setString(1, deleteId);
                    if (handleUUID) {
                        batchDeleteStmt.setString(2, puuid);
                    }
                    deleteIdList.add(deleteId);
                    batchDeleteStmt.addBatch();
                }
            }else{
                if(handleUUID && puuid == null){
                    batchDeleteStmtVersionedWithoutUUID.setLong(1, version);
                    batchDeleteStmtVersionedWithoutUUID.setString(2, deleteId);
                    deleteIdListWithoutUUID.add(deleteId);
                    batchDeleteStmtVersionedWithoutUUID.addBatch();
                }
                else {
                    batchDeleteStmtVersioned.setLong(1, version);
                    batchDeleteStmtVersioned.setString(2, deleteId);
                    if (handleUUID) {
                        batchDeleteStmtVersioned.setString(3, puuid);
                    }
                    deleteIdList.add(deleteId);
                    batchDeleteStmtVersioned.addBatch();
                }
            }
        }
        if(version != null){
            executeBatchesAndCheckOnFailures(dbh, deleteIdList, deleteIdListWithoutUUID,
                    batchDeleteStmtVersioned, batchDeleteStmtVersionedWithoutUUID, fails, handleUUID, TYPE_DELETE, traceItem);

        }else{
            executeBatchesAndCheckOnFailures(dbh, deleteIdList, deleteIdListWithoutUUID,
                batchDeleteStmt, batchDeleteStmtWithoutUUID, fails, handleUUID, TYPE_DELETE, traceItem);
        }

        if(fails.size() > 0) {
            logException(null, traceItem, LOG_EXCEPTION_DELETE, dbh, event);
            throw new SQLException(DELETE_ERROR_GENERAL);
        }
    }

}
