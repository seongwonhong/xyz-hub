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

package com.here.xyz;

import static com.here.xyz.XyzSerializable.Mappers.DEFAULT_MAPPER;
import static com.here.xyz.XyzSerializable.Mappers.getMapperForView;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.here.xyz.LazyParsable.ProxyStringReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public interface XyzSerializable {

  class Mappers {
    public static final ThreadLocal<ObjectMapper> DEFAULT_MAPPER = ThreadLocal.withInitial(
        () -> registerNewMapper(new ObjectMapper().setSerializationInclusion(Include.NON_NULL)));
    private static final ThreadLocal<ObjectMapper> PUBLIC_MAPPER = ThreadLocal.withInitial(
        () -> registerNewMapper(new ObjectMapper().setConfig(DEFAULT_MAPPER.get().getSerializationConfig().withView(Public.class))));
    private static final ThreadLocal<ObjectMapper> STATIC_MAPPER = ThreadLocal.withInitial(
        () -> registerNewMapper(new ObjectMapper().setConfig(DEFAULT_MAPPER.get().getSerializationConfig().withView(Static.class))));
    protected static final ThreadLocal<ObjectMapper> SORTED_MAPPER = ThreadLocal.withInitial(
        () -> registerNewMapper(new ObjectMapper().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .setSerializationInclusion(Include.NON_NULL)));
    private static final Collection<Class<?>> REGISTERED_SUBTYPES = new ConcurrentLinkedQueue<>();
    private static final Map<Class<?>, Class<?>> ALL_MIX_INS = new ConcurrentHashMap<>();
    private static final Collection<ObjectMapper> ALL_MAPPERS = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static boolean alwaysSerializePretty = false;

    private static ObjectMapper registerNewMapper(ObjectMapper om) {
      ALL_MAPPERS.add(om);
      om.registerSubtypes(REGISTERED_SUBTYPES.toArray(Class<?>[]::new));
      om.setMixIns(ALL_MIX_INS);
      return om;
    }

    private static void registerSubtypes(Class<?>... classes) {
      //Add the new subtypes to the list of registered subtypes so that they will be registered on new ObjectMappers
      REGISTERED_SUBTYPES.addAll(Arrays.asList(classes));
      //Register the new subtypes on all existing mappers
      ALL_MAPPERS.forEach(om -> om.registerSubtypes(classes));
    }

    private static void registerMixIn(Class<?> target, Class<?> mixinSource) {
      ALL_MIX_INS.put(target, mixinSource);
      ALL_MAPPERS.forEach(om -> om.addMixIn(target, mixinSource));
    }

    /**
     * Returns the ObjectMapper for the given view.
     * E.g., If the view is a {@link Static} view or extends from {@link Static}, the static mapper is returned.
     * @param view the view to get the mapper for
     * @return the {@link ObjectMapper} for the given view, the "default mapper" if <code>null</code> is passed as view
     */
    protected static ObjectMapper getMapperForView(Class<? extends SerializationView> view) {
      if (view == null)
        return DEFAULT_MAPPER.get();

      if (Static.class.isAssignableFrom(view))
        return STATIC_MAPPER.get();

      if (Public.class.isAssignableFrom(view))
        return PUBLIC_MAPPER.get();

      return DEFAULT_MAPPER.get();
    }
  }

  /**
   * Can be used in tests to enable pretty serialization by default.
   * @param alwaysSerializePretty Whether to serialize pretty regardless of the pretty parameter of the {@link #serialize(boolean)}
   *  methods.
   */
  static void setAlwaysSerializePretty(boolean alwaysSerializePretty) {
    Mappers.alwaysSerializePretty = alwaysSerializePretty;
  }

  /**
   * Can be used to register additional subtypes at runtime for types which will be deserialized polymorphically.
   * The subtypes will be registered on all newly created {@link ObjectMapper}s as well as on all existing ones immediately.
   * @param classes The classes to register for deserialization purposes
   */
  static void registerSubtypes(Class<?>... classes) {
    Mappers.registerSubtypes(classes);
  }

  /**
   * Add mix-in annotations to all the {@link ObjectMapper}s in order to add or override target annotations from the source
   *
   * @param target Class whose annotations to effectively override
   * @param mixinSource Class whose annotations are to be "added" to target's annotations, overriding as necessary
   */
  static void registerMixIn(Class<?> target, Class<?> mixinSource) {
    Mappers.registerMixIn(target, mixinSource);
  }

  default String serialize() {
    return serialize(this);
  }

  default String serialize(Class<? extends SerializationView> view) {
    return serialize(this, view);
  }

  @SuppressWarnings("unused")
  default String serialize(boolean pretty) {
    return serialize(this, null, pretty); //TODO: Switch to use Public mapper as default in future (rather than using the default mapper)
  }

  default String serialize(Class<? extends SerializationView> view, boolean pretty) {
    return serialize(this, view, pretty);
  }

  @SuppressWarnings("unused")
  static String serialize(Object object) {
    return serialize(object, null, false); //TODO: Switch to use Public mapper as default in future (rather than using the default mapper)
  }

  static String serialize(Object object, Class<? extends SerializationView> view) {
    return serialize(object, view, false);
  }

  static String serialize(Object object, boolean pretty) {
    return serialize(object, Public.class, pretty);
  }

  private static String serialize(Object object, Class<? extends SerializationView> view, boolean pretty) {
    ObjectMapper mapper = getMapperForView(view);
    try {
      return pretty || Mappers.alwaysSerializePretty ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(object) : mapper.writeValueAsString(object);
    }
    catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to encode as JSON: " + e.getMessage(), e);
    }
  }

  /**
   * @deprecated Please use {@link #serialize(Object)} instead.
   * @param object
   * @param typeReference
   * @return
   */
  @Deprecated
  @SuppressWarnings("unused")
  static String serialize(Object object, TypeReference typeReference) {
    try {
      return DEFAULT_MAPPER.get().writerFor(typeReference).writeValueAsString(object);
    }
    catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to encode as JSON: " + e.getMessage(), e);
    }
  }

  /**
   * Used to serialize lists of XyzSerializable objects with additional type info to keep polymorph type information that would
   * be lost otherwise, because generic type information erasure.
   * @param list The list of objects to be serialized
   * @param itemTypeReference The type reference to be applied for the items of the list
   * @return The serialized JSON string
   */
  static String serialize(List<?> list, TypeReference itemTypeReference) {
    return serialize(list, null, itemTypeReference); //TODO: Switch to use Public mapper as default in future (rather than using the default mapper)
  }

  /**
   * Used to serialize lists of XyzSerializable objects with additional type info to keep polymorph type information that would
   * be lost otherwise, because generic type information erasure.
   * @param list The list of objects to be serialized
   * @param view The view to be used for serialization
   * @param itemTypeReference The type reference to be applied for the items of the list
   * @return The serialized JSON string
   */
  static String serialize(List<?> list, Class<? extends SerializationView> view, TypeReference itemTypeReference) {
    try {
      return getMapperForView(view).writerFor(itemTypeReference).writeValueAsString(list);
    }
    catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to encode as JSON: " + e.getMessage(), e);
    }
  }

  default byte[] toByteArray() {
    return toByteArray(this);
  }

  default byte[] toByteArray(Class<? extends SerializationView> view) {
    return toByteArray(this, view);
  }

  static byte[] toByteArray(Object object) {
    return toByteArray(object, null); //TODO: Switch to use Public mapper as default in future (rather than using the default mapper)
  }

  static byte[] toByteArray(Object object, Class<? extends SerializationView> view) {
    return serialize(object, view).getBytes();
  }

  @SuppressWarnings("UnusedReturnValue")
  default Map<String, Object> toMap() {
    return toMap(this);
  }

  default Map<String, Object> toMap(Class<? extends SerializationView> view) {
    //noinspection unchecked
    return toMap(this, view);
  }

  static Map<String, Object> toMap(Object object) {
    return toMap(object, null); //TODO: Switch to use Public mapper as default in future (rather than using the default mapper)
  }

  static Map<String, Object> toMap(Object object, Class<? extends SerializationView> view) {
    return getMapperForView(view).convertValue(object, Map.class);
  }

  @SuppressWarnings("unused")
  default List<Object> toList() {
    return toList(this);
  }

  default List<Object> toList(Class<? extends SerializationView> view) {
    //noinspection unchecked
    return toList(this, view);
  }

  static List<Object> toList(Object object) {
    return toList(object, null); //TODO: Switch to use Public mapper as default in future (rather than using the default mapper)
  }

  static List<Object> toList(Object object, Class<? extends SerializationView> view) {
    return getMapperForView(view).convertValue(object, List.class);
  }

  @SuppressWarnings("unchecked")
  static <T extends Typed> T deserialize(InputStream is) throws JsonProcessingException {
    return (T) deserialize(is, Typed.class);
  }

  static <T> T deserialize(InputStream is, Class<T> klass) throws JsonProcessingException {
    try (Scanner scanner = new java.util.Scanner(is)) {
      return deserialize(scanner.useDelimiter("\\A").next(), klass);
    }
  }

  static <T> T deserialize(InputStream is, TypeReference<T> type) throws JsonProcessingException {
    try (Scanner scanner = new java.util.Scanner(is)) {
      return deserialize(scanner.useDelimiter("\\A").next(), type);
    }
  }

  static <T> MappingIterator<T> deserializeJsonLines(InputStream stream, Class<T> klass) throws IOException {
    return DEFAULT_MAPPER.get().readerFor(klass).readValues(stream);
  }

  static <T> MappingIterator<T> deserializeJsonLines(InputStream stream, TypeReference<T> type) throws IOException {
    return DEFAULT_MAPPER.get().readerFor(type).readValues(stream);
  }

  static <T extends Typed> T deserialize(byte[] bytes) throws JsonProcessingException {
    return (T) deserialize(bytes, Typed.class);
  }

  static <T> T deserialize(byte[] bytes, Class<T> klass) throws JsonProcessingException {
    return deserialize(new String(bytes), klass);
  }

  static <T> T deserialize(byte[] bytes, TypeReference<T> type) throws JsonProcessingException {
    return deserialize(new String(bytes), type);
  }

  static <T extends Typed> T deserialize(String string) throws JsonProcessingException {
    //noinspection unchecked
    return (T) deserialize(string, Typed.class);
  }

  static <T> T deserialize(String string, Class<T> klass) throws JsonProcessingException {
    /*
    Jackson always wraps larger strings, with a string reader, which hides the original string from the lazy raw deserializer.
    To circumvent that, wrap the source string with a custom string reader, which provides access to the input string.
     */
    try {
      return DEFAULT_MAPPER.get().readValue(new ProxyStringReader(string), klass);
    }
    catch (JsonProcessingException e) {
      //NOTE: This catch block must stay, because JsonProcessingException extends IOException
      throw e;
    }
    catch (IOException e) {
      return null;
    }
  }

  @SuppressWarnings("unused")
  static <T> T deserialize(String string, TypeReference<T> type) throws JsonProcessingException {
    return DEFAULT_MAPPER.get().readerFor(type).readValue(string);
  }

  static <T extends Typed> T fromMap(Map<String, Object> map) {
    return (T) fromMap(map, Typed.class);
  }

  @SuppressWarnings("unused")
  static <T> T fromMap(Map<String, Object> map, Class<T> klass) {
    return DEFAULT_MAPPER.get().convertValue(map, klass);
  }

  static <T> T fromMap(Map<String, Object> map, TypeReference<T> type) {
    return DEFAULT_MAPPER.get().convertValue(map, type);
  }

  @SuppressWarnings("unchecked")
  default <T extends XyzSerializable> T copy() {
    //TODO: Use maps instead of a String for cloning!
    try {
      //noinspection unchecked
      return (T) XyzSerializable.deserialize(serialize(), getClass());
    }
    catch (Exception e) {
      return null;
    }
  }

  static boolean equals(Object o1, Object o2) {
    return equals(o1, o2, null);
  }

  static boolean equals(Object o1, Object o2, Class<? extends SerializationView> view) {
    if (o1 == null || o2 == null)
      return o1 == o2;

    return toMap(o1, view).equals(toMap(o2, view));
  }

  interface SerializationView {}

  /**
   * Used as a JsonView on {@link Payload} models to indicate that a property should be serialized as part of public responses.
   * (e.g. when it comes to REST API responses)
   */
  @SuppressWarnings("WeakerAccess")
  class Public implements SerializationView {}

  /**
   * Used as a JsonView on {@link Payload} models to indicate that a property should be serialized in the persistence layer.
   * (e.g. when it comes to saving the instance to a database)
   */
  class Static implements SerializationView {}

  /**
   * Used as a JsonView on {@link XyzSerializable} models to indicate that a property should be serialized as part of internal types.
   * (e.g. when it comes to communication between software components)
   */
  class Internal implements SerializationView {}
}