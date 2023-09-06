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

package com.here.xyz.httpconnector.util.jobs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.here.xyz.Typed;
import com.here.xyz.httpconnector.util.jobs.DatasetDescription.Files;
import com.here.xyz.httpconnector.util.jobs.DatasetDescription.Map;
import com.here.xyz.httpconnector.util.jobs.DatasetDescription.Space;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Map.class, name = "Map"),
    @JsonSubTypes.Type(value = Space.class, name = "Space"),
    @JsonSubTypes.Type(value = Files.class, name = "Files")
})
public abstract class DatasetDescription implements Typed {

  public abstract static class Identifiable extends DatasetDescription {

    private String id;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public <T extends Identifiable> T withId(String id) {
      setId(id);
      return (T) this;
    }
  }

  public static class Files extends DatasetDescription {

  }

  public static class Map extends Identifiable {

  }

  public static class Space extends Identifiable {

  }
}
