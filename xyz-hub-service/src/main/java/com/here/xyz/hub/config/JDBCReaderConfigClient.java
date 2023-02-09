/*
 * Copyright (C) 2017-2023 HERE Europe B.V.
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

package com.here.xyz.hub.config;

import static com.here.xyz.hub.config.JDBCConfig.READER_TABLE;

import com.here.xyz.models.hub.Reader;
import com.here.xyz.models.hub.Subscription;
import com.here.xyz.psql.SQLQuery;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.sql.SQLClient;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;

public class JDBCReaderConfigClient extends ReaderConfigClient{
  private static final Logger logger = LogManager.getLogger();

  private static JDBCReaderConfigClient instance;
  SQLClient client;

  private JDBCReaderConfigClient() {
    this.client = JDBCConfig.getClient();
  }

  public static ReaderConfigClient getInstance() {
    if (instance == null) {
      instance = new JDBCReaderConfigClient();
    }
    return instance;
  }

  @Override
  public Future<Reader> getReader(Marker marker, String id, String spaceId) {
    Promise<Reader> p = Promise.promise();
    SQLQuery query = new SQLQuery("SELECT id, space, version FROM " + READER_TABLE + " WHERE id = ? AND space = ?", id, spaceId);
    client.queryWithParams(query.text(), new JsonArray(query.parameters()), out -> {
      if (out.succeeded()) {
        Optional<Reader> reader = out.result().getRows().stream().map(r->new Reader()
                .withId(r.getString("id"))
                .withSpaceId(r.getString("space"))
                .withVersion(r.getLong("version"))
        ).findFirst();
        if (reader.isPresent()) {
          p.complete(reader.get());
        }
        else
          p.complete();
      }
      else
        p.fail(out.cause());
    });
    return p.future();
  }

  @Override
  public Future<List<Reader>> getReaders(Marker marker, String id, List<String> spaceIds) {
    List<String> params = spaceIds.stream().map(e->"?").collect(Collectors.toList());
    SQLQuery query = new SQLQuery("WHERE id=?", id);
    query.append(new SQLQuery("AND space IN (" + StringUtils.join(params, ",")+")", spaceIds.toArray()));
    return _getReaders(marker, query);
  }

  @Override
  public Future<List<Reader>> getReaders(Marker marker, String spaceId) {
    return _getReaders(marker, new SQLQuery("WHERE space=?", spaceId));
  }

  @Override
  public Future<List<Reader>> getReaders(Marker marker, List<String> spaceIds) {
    List<String> params = spaceIds.stream().map(e->"?").collect(Collectors.toList());
    SQLQuery query = new SQLQuery("WHERE space IN (" + StringUtils.join(params, ",")+")", spaceIds.toArray());
    return _getReaders(marker, query);
  }

  @Override
  public Future<List<Reader>> getAllReaders(Marker marker) {
    return _getReaders(marker, null);
  }

  private Future<List<Reader>> _getReaders(Marker marker, SQLQuery whereClause) {
    Promise<List<Reader>> p = Promise.promise();
    SQLQuery query = new SQLQuery("SELECT id, space, version FROM " + READER_TABLE );
    if(whereClause != null )
      whereClause.append(whereClause);
    client.queryWithParams(query.text(), new JsonArray(query.parameters()), out -> {
      if (out.succeeded()) {
        List<Reader> reader = out.result().getRows().stream().map(r->new Reader()
            .withId(r.getString("id"))
            .withSpaceId(r.getString("space"))
            .withVersion(r.getLong("version"))
        ).collect(Collectors.toList());
        p.complete(reader);
      }
      else
        p.fail(out.cause());
    });
    return p.future();
  }

  @Override
  public Future<Long> increaseVersion(Marker marker, String spaceId, String readerId, Long newVersion) {
    return null;
  }

  @Override
  public Future<Void> storeReader(Marker marker, Reader reader) {
    Promise<Void> p = Promise.promise();

    final SQLQuery query = new SQLQuery("INSERT INTO " + READER_TABLE + " (id, space, version) VALUES (?, ?, ?) " +
        "ON CONFLICT (id,space) DO " +
        "UPDATE SET id = ?, space = ?, version = ?",
        reader.getId(), reader.getSpaceId(), reader.getVersion(),
        reader.getId(), reader.getSpaceId(), reader.getVersion());

    client.updateWithParams(query.text(), new JsonArray(query.parameters()), out -> {
      if (out.succeeded()) {
        p.complete();
      } else {
        p.fail(out.cause());
      }
    });
    return p.future();
  }

  @Override
  public Future<Reader> deleteReader(Marker marker, String id, String spaceId) {
    final SQLQuery query = new SQLQuery("DELETE FROM " + READER_TABLE + " WHERE id = ? AND space = ?", id, spaceId);
    return getReader(marker, id, spaceId).compose(reader -> JDBCConfig.updateWithParams(query).map(reader));
  }

  @Override
  public Future<List<Reader>> deleteReaders(Marker marker, String spaceId) {
    final SQLQuery query = new SQLQuery("DELETE FROM " + READER_TABLE + " WHERE space = ?", spaceId);
    return getReaders(marker, spaceId)
        .compose(readers -> JDBCConfig.updateWithParams(query)
        .map(readers));
  }
}
