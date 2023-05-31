/*
 * Copyright 2023 The Fury authors
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.resolver;

import static io.fury.codegen.Expression.Invoke.inlineInvoke;
import static io.fury.codegen.ExpressionUtils.eq;
import static io.fury.serializer.CodegenSerializer.loadCodegenSerializer;
import static io.fury.serializer.CodegenSerializer.loadCompatibleCodegenSerializer;
import static io.fury.serializer.CodegenSerializer.supportCodegenForJavaSerialization;
import static io.fury.type.TypeUtils.PRIMITIVE_INT_TYPE;
import static io.fury.type.TypeUtils.PRIMITIVE_SHORT_TYPE;
import static io.fury.type.TypeUtils.getRawType;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Primitives;
import com.google.common.reflect.TypeToken;
import io.fury.Fury;
import io.fury.Language;
import io.fury.annotation.Internal;
import io.fury.builder.CodecUtils;
import io.fury.builder.Generated;
import io.fury.builder.JITContext;
import io.fury.codegen.Expression;
import io.fury.codegen.ExpressionUtils;
import io.fury.collection.IdentityMap;
import io.fury.collection.IdentityObjectIntMap;
import io.fury.collection.LongMap;
import io.fury.collection.ObjectMap;
import io.fury.collection.Tuple2;
import io.fury.exception.InsecureException;
import io.fury.memory.MemoryBuffer;
import io.fury.serializer.ArraySerializers;
import io.fury.serializer.BufferSerializers;
import io.fury.serializer.ChildContainerSerializers;
import io.fury.serializer.CodegenSerializer;
import io.fury.serializer.CollectionSerializers;
import io.fury.serializer.CompatibleMode;
import io.fury.serializer.CompatibleSerializer;
import io.fury.serializer.ExternalizableSerializer;
import io.fury.serializer.JavaSerializer;
import io.fury.serializer.JdkProxySerializer;
import io.fury.serializer.LambdaSerializer;
import io.fury.serializer.LocaleSerializer;
import io.fury.serializer.MapSerializers;
import io.fury.serializer.MetaSharedSerializer;
import io.fury.serializer.ObjectSerializer;
import io.fury.serializer.OptionalSerializers;
import io.fury.serializer.ReplaceResolveSerializer;
import io.fury.serializer.Serializer;
import io.fury.serializer.SerializerFactory;
import io.fury.serializer.Serializers;
import io.fury.serializer.StringSerializer;
import io.fury.serializer.SynchronizedSerializers;
import io.fury.serializer.TimeSerializers;
import io.fury.serializer.UnexistedClassSerializers.UnexistedClassSerializer;
import io.fury.serializer.UnexistedClassSerializers.UnexistedMetaSharedClass;
import io.fury.serializer.UnexistedClassSerializers.UnexistedSkipClass;
import io.fury.serializer.UnmodifiableSerializers;
import io.fury.type.ClassDef;
import io.fury.type.Descriptor;
import io.fury.type.GenericType;
import io.fury.type.TypeUtils;
import io.fury.util.Functions;
import io.fury.util.LoggerFactory;
import io.fury.util.Platform;
import io.fury.util.ReflectionUtils;
import io.fury.util.StringUtils;
import java.io.Externalizable;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * Class registry for types of serializing objects, responsible for reading/writing types, setting
 * up relations between serializer and types.
 *
 * @author chaokunyang
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ClassResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ClassResolver.class);
  private static final Class<Serializer> ArrowSerializersClass;

  static {
    List<String> splits = Arrays.asList(ClassResolver.class.getName().split("\\."));
    String furyPackage = String.join(".", splits.subList(0, splits.size() - 2));
    // compatible with maven shade.
    String className = furyPackage + ".format.vectorized.ArrowSerializers";
    Class<Serializer> cls = null;
    try {
      cls =
          (Class<Serializer>) Class.forName(className, true, ClassResolver.class.getClassLoader());
      LOG.debug("Loaded arrow serializer classes.");
    } catch (ClassNotFoundException e) {
      LOG.debug(
          "`fury-format` dependency not included, skip adding serializer for class {}. "
              + "If you want to use fury-format, please include fury-format dependency.",
          className);
    } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
      LOG.info(
          "Add serializer for class {} failed. Apache arrow relies on the internals of `java.nio`. "
              + "If you are using jdk17+, please open module `java.base` to unnamed module",
          className);
    }
    ArrowSerializersClass = cls;
  }

  public static final byte USE_CLASS_VALUE = 0;
  public static final byte USE_STRING_ID = 1;
  // preserve 0 as flag for class id not set in ClassInfo`
  public static final short NO_CLASS_ID = (short) 0;
  public static final short LAMBDA_STUB_ID = 1;
  public static final short JDK_PROXY_STUB_ID = 2;
  public static final short REPLACE_STUB_ID = 3;
  // Note: following pre-defined class id should be continuous, since they may be used based range.
  public static final short PRIMITIVE_VOID_CLASS_ID = (short) (REPLACE_STUB_ID + 1);
  public static final short PRIMITIVE_BOOLEAN_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 1);
  public static final short PRIMITIVE_BYTE_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 2);
  public static final short PRIMITIVE_CHAR_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 3);
  public static final short PRIMITIVE_SHORT_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 4);
  public static final short PRIMITIVE_INT_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 5);
  public static final short PRIMITIVE_FLOAT_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 6);
  public static final short PRIMITIVE_LONG_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 7);
  public static final short PRIMITIVE_DOUBLE_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 8);
  public static final short VOID_CLASS_ID = (short) (PRIMITIVE_DOUBLE_CLASS_ID + 1);
  public static final short BOOLEAN_CLASS_ID = (short) (VOID_CLASS_ID + 1);
  public static final short BYTE_CLASS_ID = (short) (VOID_CLASS_ID + 2);
  public static final short CHAR_CLASS_ID = (short) (VOID_CLASS_ID + 3);
  public static final short SHORT_CLASS_ID = (short) (VOID_CLASS_ID + 4);
  public static final short INTEGER_CLASS_ID = (short) (VOID_CLASS_ID + 5);
  public static final short FLOAT_CLASS_ID = (short) (VOID_CLASS_ID + 6);
  public static final short LONG_CLASS_ID = (short) (VOID_CLASS_ID + 7);
  public static final short DOUBLE_CLASS_ID = (short) (VOID_CLASS_ID + 8);
  public static final short STRING_CLASS_ID = (short) (VOID_CLASS_ID + 9);
  public static final short PRIMITIVE_BOOLEAN_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 1);
  public static final short PRIMITIVE_BYTE_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 2);
  public static final short PRIMITIVE_CHAR_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 3);
  public static final short PRIMITIVE_SHORT_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 4);
  public static final short PRIMITIVE_INT_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 5);
  public static final short PRIMITIVE_FLOAT_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 6);
  public static final short PRIMITIVE_LONG_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 7);
  public static final short PRIMITIVE_DOUBLE_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 8);
  public static final short STRING_ARRAY_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 1);
  public static final short OBJECT_ARRAY_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 2);
  public static final short ARRAYLIST_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 3);
  public static final short HASHMAP_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 4);
  public static final short HASHSET_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 5);
  public static final short CLASS_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 6);
  private static final int initialCapacity = 128;
  // use a lower load factor to minimize hash collision
  private static final float loadFactor = 0.25f;
  private static final float furyMapLoadFactor = 0.25f;
  private static final String META_SHARE_FIELDS_INFO_KEY = "shareFieldsInfo";
  private static final ClassInfo NIL_CLASS_INFO =
      new ClassInfo(null, null, null, null, false, null, null, ClassResolver.NO_CLASS_ID);

  private final Fury fury;
  private ClassInfo[] registeredId2ClassInfo = new ClassInfo[] {};

  // IdentityMap has better lookup performance, when loadFactor is 0.05f, performance is better
  private final IdentityMap<Class<?>, ClassInfo> classInfoMap =
      new IdentityMap<>(initialCapacity, furyMapLoadFactor);
  private ClassInfo classInfoCache;
  private final ObjectMap<EnumStringBytes, Class<?>> classNameBytes2Class =
      new ObjectMap<>(initialCapacity, furyMapLoadFactor);
  // Every deserialization for unregistered class will query it, performance is important.
  private final ObjectMap<ClassNameBytes, Class<?>> compositeClassNameBytes2Class =
      new ObjectMap<>(initialCapacity, furyMapLoadFactor);
  private final HashMap<Short, Class<?>> typeIdToClassXLangMap =
      new HashMap<>(initialCapacity, loadFactor);
  private final HashMap<String, Class<?>> typeTagToClassXLangMap =
      new HashMap<>(initialCapacity, loadFactor);
  private final EnumStringResolver enumStringResolver;
  private final boolean metaContextShareEnabled;
  private final Map<Class<?>, ClassDef> classDefMap = new HashMap<>();
  private Class<?> currentReadClass;
  // class id of last default registered class.
  private short innerEndClassId;
  private final ExtRegistry extRegistry;

  private static class ExtRegistry {
    private short registeredClassIdCounter = 0;
    private final LongMap<Class<?>> registeredId2Classes = new LongMap<>(initialCapacity);
    private SerializerFactory serializerFactory;
    private final IdentityMap<Class<?>, Short> registeredClassIdMap =
        new IdentityMap<>(initialCapacity);
    private final Map<String, Class<?>> registeredClasses = new HashMap<>(initialCapacity);
    // avoid potential recursive call for seq codec generation.
    // ex. A->field1: B, B.field1: A
    private final Set<Class<?>> getClassCtx = new HashSet<>();
    private final Map<Class<?>, FieldResolver> fieldResolverMap = new HashMap<>();
    private final Map<Long, Tuple2<ClassDef, ClassInfo>> classIdToDef = new HashMap<>();
    // TODO(chaokunyang) Better to  use soft reference, see ObjectStreamClass.
    private final ConcurrentHashMap<Tuple2<Class<?>, Boolean>, SortedMap<Field, Descriptor>>
        descriptorsCache = new ConcurrentHashMap<>();
  }

  public ClassResolver(Fury fury) {
    this.fury = fury;
    enumStringResolver = fury.getEnumStringResolver();
    classInfoCache = NIL_CLASS_INFO;
    metaContextShareEnabled = fury.getConfig().isMetaContextShareEnabled();
    extRegistry = new ExtRegistry();
  }

  public void initialize() {
    register(LambdaSerializer.ReplaceStub.class, LAMBDA_STUB_ID);
    register(JdkProxySerializer.ReplaceStub.class, JDK_PROXY_STUB_ID);
    register(ReplaceResolveSerializer.ReplaceStub.class, REPLACE_STUB_ID);
    registerWithCheck(void.class, PRIMITIVE_VOID_CLASS_ID);
    registerWithCheck(boolean.class, PRIMITIVE_BOOLEAN_CLASS_ID);
    registerWithCheck(byte.class, PRIMITIVE_BYTE_CLASS_ID);
    registerWithCheck(char.class, PRIMITIVE_CHAR_CLASS_ID);
    registerWithCheck(short.class, PRIMITIVE_SHORT_CLASS_ID);
    registerWithCheck(int.class, PRIMITIVE_INT_CLASS_ID);
    registerWithCheck(float.class, PRIMITIVE_FLOAT_CLASS_ID);
    registerWithCheck(long.class, PRIMITIVE_LONG_CLASS_ID);
    registerWithCheck(double.class, PRIMITIVE_DOUBLE_CLASS_ID);
    registerWithCheck(Void.class, VOID_CLASS_ID);
    registerWithCheck(Boolean.class, BOOLEAN_CLASS_ID);
    registerWithCheck(Byte.class, BYTE_CLASS_ID);
    registerWithCheck(Character.class, CHAR_CLASS_ID);
    registerWithCheck(Short.class, SHORT_CLASS_ID);
    registerWithCheck(Integer.class, INTEGER_CLASS_ID);
    registerWithCheck(Float.class, FLOAT_CLASS_ID);
    registerWithCheck(Long.class, LONG_CLASS_ID);
    registerWithCheck(Double.class, DOUBLE_CLASS_ID);
    registerWithCheck(String.class, STRING_CLASS_ID);
    registerWithCheck(boolean[].class, PRIMITIVE_BOOLEAN_ARRAY_CLASS_ID);
    registerWithCheck(byte[].class, PRIMITIVE_BYTE_ARRAY_CLASS_ID);
    registerWithCheck(char[].class, PRIMITIVE_CHAR_ARRAY_CLASS_ID);
    registerWithCheck(short[].class, PRIMITIVE_SHORT_ARRAY_CLASS_ID);
    registerWithCheck(int[].class, PRIMITIVE_INT_ARRAY_CLASS_ID);
    registerWithCheck(float[].class, PRIMITIVE_FLOAT_ARRAY_CLASS_ID);
    registerWithCheck(long[].class, PRIMITIVE_LONG_ARRAY_CLASS_ID);
    registerWithCheck(double[].class, PRIMITIVE_DOUBLE_ARRAY_CLASS_ID);
    registerWithCheck(String[].class, STRING_ARRAY_CLASS_ID);
    registerWithCheck(Object[].class, OBJECT_ARRAY_CLASS_ID);
    registerWithCheck(ArrayList.class, ARRAYLIST_CLASS_ID);
    registerWithCheck(HashMap.class, HASHMAP_CLASS_ID);
    registerWithCheck(HashSet.class, HASHSET_CLASS_ID);
    registerWithCheck(Class.class, CLASS_CLASS_ID);
    addDefaultSerializers();
    registerDefaultClasses();
    innerEndClassId = extRegistry.registeredClassIdCounter;
  }

  private void addDefaultSerializers() {
    // primitive types will be boxed.
    addDefaultSerializer(String.class, new StringSerializer(fury));
    Serializers.registerDefaultSerializers(fury);
    ArraySerializers.registerDefaultSerializers(fury);
    TimeSerializers.registerDefaultSerializers(fury);
    OptionalSerializers.registerDefaultSerializers(fury);
    CollectionSerializers.registerDefaultSerializers(fury);
    MapSerializers.registerDefaultSerializers(fury);
    addDefaultSerializer(Locale.class, new LocaleSerializer(fury));
    addDefaultSerializer(
        LambdaSerializer.ReplaceStub.class,
        new LambdaSerializer(fury, LambdaSerializer.ReplaceStub.class));
    addDefaultSerializer(
        JdkProxySerializer.ReplaceStub.class,
        new JdkProxySerializer(fury, JdkProxySerializer.ReplaceStub.class));
    addDefaultSerializer(
        ReplaceResolveSerializer.ReplaceStub.class,
        new ReplaceResolveSerializer(fury, ReplaceResolveSerializer.ReplaceStub.class));
    SynchronizedSerializers.registerSerializers(fury);
    UnmodifiableSerializers.registerSerializers(fury);
    if (metaContextShareEnabled) {
      addDefaultSerializer(
          UnexistedMetaSharedClass.class, new UnexistedClassSerializer(fury, null));
    }
    if (ArrowSerializersClass != null) {
      try {
        Method method = ArrowSerializersClass.getDeclaredMethod("registerSerializers", Fury.class);
        method.setAccessible(true);
        method.invoke(null, fury);
      } catch (NoSuchMethodException e) {
        throw new IllegalStateException("unreachable", e);
      } catch (InvocationTargetException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private void addDefaultSerializer(Class<?> type, Class<? extends Serializer> serializerClass) {
    addDefaultSerializer(type, Serializers.newSerializer(fury, type, serializerClass));
  }

  private void addDefaultSerializer(Class type, Serializer serializer) {
    registerSerializer(type, serializer);
    register(type);
  }

  private void registerDefaultClasses() {
    register(Object.class, Object[].class, Void.class);
    register(ByteBuffer.allocate(1).getClass());
    register(ByteBuffer.allocateDirect(1).getClass());
    register(Comparator.naturalOrder().getClass());
    register(Comparator.reverseOrder().getClass());
    register(ConcurrentHashMap.class);
    register(ArrayBlockingQueue.class);
    register(LinkedBlockingQueue.class);
    register(Boolean[].class, Byte[].class, Short[].class, Character[].class);
    register(Integer[].class, Float[].class, Long[].class, Double[].class);
    register(AtomicBoolean.class);
    register(AtomicInteger.class);
    register(AtomicLong.class);
    register(AtomicReference.class);
    register(EnumSet.allOf(Language.class).getClass());
    register(EnumSet.of(Language.JAVA).getClass());
    register(UnexistedMetaSharedClass.class, UnexistedSkipClass.class);
  }

  /** register class. */
  public void register(Class<?> cls) {
    if (!extRegistry.registeredClassIdMap.containsKey(cls)) {
      while (extRegistry.registeredId2Classes.containsKey(extRegistry.registeredClassIdCounter)) {
        extRegistry.registeredClassIdCounter++;
      }
      register(cls, extRegistry.registeredClassIdCounter);
    }
  }

  public void register(Class<?>... classes) {
    for (Class<?> cls : classes) {
      register(cls);
    }
  }

  /** register class with given type tag which will be used for cross-language serialization. */
  public void register(Class<?> cls, String typeTag) {
    Preconditions.checkArgument(!typeTagToClassXLangMap.containsKey(typeTag));
    throw new UnsupportedOperationException();
  }

  public void register(Class<?> cls, int classId) {
    Preconditions.checkArgument(classId >= 0 && classId < Short.MAX_VALUE);
    short id = (short) classId;
    if (!extRegistry.registeredClassIdMap.containsKey(cls)) {
      if (extRegistry.registeredClasses.containsKey(cls.getName())) {
        throw new IllegalArgumentException(
            String.format(
                "Class %s with name %s has been registered, registering class with same name are not allowed.",
                extRegistry.registeredClasses.get(cls.getName()), cls.getName()));
      }
      Class<?> idToClass = extRegistry.registeredId2Classes.get(id);
      if (idToClass != null) {
        throw new IllegalArgumentException(
            String.format(
                "Class %s with id %s has been registered, registering class %s with same id are not allowed.",
                idToClass, id, cls.getName()));
      }
      extRegistry.registeredClassIdMap.put(cls, id);
      if (registeredId2ClassInfo.length <= id) {
        ClassInfo[] tmp = new ClassInfo[(id + 1) * 2];
        System.arraycopy(registeredId2ClassInfo, 0, tmp, 0, registeredId2ClassInfo.length);
        registeredId2ClassInfo = tmp;
      }
      ClassInfo classInfo = classInfoMap.get(cls);
      if (classInfo != null) {
        classInfo.classId = id;
      } else {
        classInfo = new ClassInfo(this, cls, null, null, id);
        // make `extRegistry.registeredClassIdMap` and `classInfoMap` share same classInfo
        // instances.
        classInfoMap.put(cls, classInfo);
      }
      // serializer will be set lazily in `addSerializer` method if it's null.
      registeredId2ClassInfo[id] = classInfo;
      extRegistry.registeredClasses.put(cls.getName(), cls);
      extRegistry.registeredClassIdCounter++;
      extRegistry.registeredId2Classes.put(id, cls);
    }
  }

  /** register class with given id. */
  public void registerWithCheck(Class<?> cls, short id) {
    if (extRegistry.registeredClassIdMap.containsKey(cls)) {
      throw new IllegalArgumentException(
          String.format(
              "" + "Class %s already registered with id %s.",
              cls, extRegistry.registeredClassIdMap.get(cls)));
    }
    register(cls, id);
  }

  public Short getRegisteredClassId(Class<?> cls) {
    return extRegistry.registeredClassIdMap.get(cls);
  }

  public Class<?> getRegisteredClass(short id) {
    if (id < registeredId2ClassInfo.length) {
      ClassInfo classInfo = registeredId2ClassInfo[id];
      if (classInfo != null) {
        return classInfo.cls;
      }
    }
    return null;
  }

  public List<Class<?>> getRegisteredClasses() {
    return Arrays.stream(registeredId2ClassInfo)
        .filter(Objects::nonNull)
        .map(info -> info.cls)
        .collect(Collectors.toList());
  }

  /**
   * Mark non-inner registered final types as non-final to write class def for those types. Note if
   * a class is registered but not an inner class with inner serializer, it will still be taken as
   * non-final to write class def, so that it can be deserialized by the peer still..
   */
  public boolean isFinal(Class<?> clz) {
    if (Modifier.isFinal(clz.getModifiers())) {
      if (fury.getConfig().isMetaContextShareEnabled()) {
        boolean isInnerClass = isInnerClass(clz);
        if (!isInnerClass) {
          return false;
        } else {
          // can't create final map/collection type using TypeUtils.mapOf(TypeToken<K>,
          // TypeToken<V>)
          return !Map.class.isAssignableFrom(clz) && !Collection.class.isAssignableFrom(clz);
        }
      } else {
        return true;
      }
    }
    return false;
  }

  /** Returns true if <code>cls</code> is fury inner registered class. */
  boolean isInnerClass(Class<?> cls) {
    Short classId = extRegistry.registeredClassIdMap.get(cls);
    if (classId == null) {
      ClassInfo classInfo = getClassInfo(cls, false);
      if (classInfo != null) {
        classId = classInfo.getClassId();
      }
    }
    return classId != null && classId != NO_CLASS_ID && classId < innerEndClassId;
  }

  /**
   * Return true if the class has jdk `writeReplace`/`readResolve` method defined, which we need to
   * use {@link ReplaceResolveSerializer}.
   */
  public static boolean useReplaceResolveSerializer(Class<?> clz) {
    // FIXME class with `writeReplace` method defined should be Serializable,
    //  but hessian ignores this check and many existing system are using hessian.
    return (JavaSerializer.getWriteReplaceMethod(clz) != null)
        || JavaSerializer.getReadResolveMethod(clz) != null;
  }

  /**
   * Return true if a class satisfy following requirements.
   * <li>implements {@link Serializable}
   * <li>is not an {@link Enum}
   * <li>is not an array
   * <li>Doesn't have {@code readResolve}/{@code writePlace} method
   * <li>has {@code readObject}/{@code writeObject} method, but doesn't implements {@link
   *     Externalizable}
   * <li/>
   */
  public static boolean requireJavaSerialization(Class<?> clz) {
    if (clz.isEnum() || clz.isArray()) {
      return false;
    }
    if (ReflectionUtils.isDynamicGeneratedCLass(clz)) {
      // use corresponding serializer.
      return false;
    }
    if (!Serializable.class.isAssignableFrom(clz)) {
      return false;
    }
    if (useReplaceResolveSerializer(clz)) {
      return false;
    }
    if (Externalizable.class.isAssignableFrom(clz)) {
      return false;
    } else {
      return JavaSerializer.getReadObjectMethod(clz) != null
          || JavaSerializer.getWriteObjectMethod(clz) != null;
    }
  }

  /**
   * Register a Serializer.
   *
   * @param type class needed to be serialized/deserialized
   * @param serializerClass serializer class can be created with {@link Serializers#newSerializer}
   * @param <T> type of class
   */
  public <T> void registerSerializer(Class<T> type, Class<? extends Serializer> serializerClass) {
    registerSerializer(type, Serializers.newSerializer(fury, type, serializerClass));
  }

  /**
   * If a serializer exists before, it will be replaced by new serializer.
   *
   * @param type class needed to be serialized/deserialized
   * @param serializer serializer for object of {@code type}
   */
  public void registerSerializer(Class<?> type, Serializer<?> serializer) {
    if (!extRegistry.registeredClassIdMap.containsKey(type)
        && fury.getLanguage() == Language.JAVA) {
      register(type);
    }
    addSerializer(type, serializer);
  }

  public void setSerializerFactory(SerializerFactory serializerFactory) {
    this.extRegistry.serializerFactory = serializerFactory;
  }

  public SerializerFactory getSerializerFactory() {
    return extRegistry.serializerFactory;
  }

  /**
   * Set the serializer for <code>cls</code>, overwrite serializer if exists. Note if class info is
   * already related with a class, this method should try to reuse that class info, otherwise jit
   * callback to update serializer won't take effect in some cases since it can't change that
   * classinfo.
   */
  public <T> void setSerializer(Class<T> cls, Serializer<T> serializer) {
    addSerializer(cls, serializer);
  }

  /**
   * Reset serializer if <code>serializer</code> is not null, otherwise clear serializer for <code>
   * cls</code>.
   *
   * @see #setSerializer
   * @see #clearSerializer
   * @see #createSerializerSafe
   */
  public <T> void resetSerializer(Class<T> cls, Serializer<T> serializer) {
    if (serializer == null) {
      clearSerializer(cls);
    } else {
      setSerializer(cls, serializer);
    }
  }

  /**
   * Set serializer to avoid circular error when there is a serializer query for fields by {@link
   * #getClassInfo} and {@link #getSerializer(Class)} which access current creating serializer. This
   * method is used to avoid overwriting existing serializer for class when creating a data
   * serializer for serialization of parts fields of a class.
   */
  public <T> void setSerializerIfAbsent(Class<T> cls, Serializer<T> serializer) {
    Serializer<T> s = getSerializer(cls, false);
    if (s == null) {
      setSerializer(cls, serializer);
    }
  }

  /** Clear serializer associated with <code>cls</code> if not null. */
  public void clearSerializer(Class<?> cls) {
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo != null) {
      classInfo.serializer = null;
    }
  }

  /** Ass serializer for specified class. */
  private void addSerializer(Class<?> type, Serializer<?> serializer) {
    Preconditions.checkNotNull(serializer);
    String typeTag = null;
    short typeId = serializer.getCrossLanguageTypeId();
    if (typeId != Fury.NOT_SUPPORT_CROSS_LANGUAGE) {
      if (typeId > Fury.NOT_SUPPORT_CROSS_LANGUAGE) {
        typeIdToClassXLangMap.put(typeId, type);
      }
      if (typeId == Fury.FURY_TYPE_TAG_ID) {
        typeTag = serializer.getCrossLanguageTypeTag();
        typeTagToClassXLangMap.put(typeTag, type);
      }
    }
    ClassInfo classInfo;
    Short classId = extRegistry.registeredClassIdMap.get(type);
    // set serializer for class if it's registered by now.
    if (classId != null) {
      classInfo = registeredId2ClassInfo[classId];
      classInfo.serializer = serializer;
    } else {
      if (serializer instanceof ReplaceResolveSerializer) {
        classId = REPLACE_STUB_ID;
      } else {
        classId = NO_CLASS_ID;
      }
      classInfo = classInfoMap.get(type);
    }
    if (classInfo == null || typeTag != null || classId != classInfo.classId) {
      classInfo = new ClassInfo(this, type, typeTag, serializer, classId);
    } else {
      classInfo.serializer = serializer;
    }
    // make `extRegistry.registeredClassIdMap` and `classInfoMap` share same classInfo instances.
    classInfoMap.put(type, classInfo);
  }

  @SuppressWarnings("unchecked")
  public <T> Serializer<T> getSerializer(Class<T> cls, boolean createIfNotExist) {
    Preconditions.checkNotNull(cls);
    if (createIfNotExist) {
      return getSerializer(cls);
    }
    ClassInfo classInfo = classInfoMap.get(cls);
    return classInfo == null ? null : (Serializer<T>) classInfo.serializer;
  }

  /** Get or create serializer for <code>cls</code>. */
  @SuppressWarnings("unchecked")
  public <T> Serializer<T> getSerializer(Class<T> cls) {
    Preconditions.checkNotNull(cls);
    return (Serializer<T>) getOrUpdateClassInfo(cls).serializer;
  }

  public Class<? extends Serializer> getSerializerClass(Class<?> cls) {
    boolean codegen =
        supportCodegenForJavaSerialization(cls) && fury.getConfig().isCodeGenEnabled();
    return getSerializerClass(cls, codegen);
  }

  public Class<? extends Serializer> getSerializerClass(Class<?> cls, boolean codegen) {
    if (cls.isPrimitive()) {
      cls = Primitives.wrap(cls);
    }
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo != null && classInfo.serializer != null) {
      // Note: need to check `classInfo.serializer != null`, because sometimes `cls` is already
      // serialized, which will create a class info with serializer null, see `#writeClassInternal`
      return classInfo.serializer.getClass();
    } else {
      if (cls.isEnum()) {
        return Serializers.EnumSerializer.class;
      } else if (Enum.class.isAssignableFrom(cls) && cls != Enum.class) {
        // handles an enum value that is an inner class. Eg: enum A {b{}};
        return Serializers.EnumSerializer.class;
      } else if (EnumSet.class.isAssignableFrom(cls)) {
        return CollectionSerializers.EnumSetSerializer.class;
      } else if (Charset.class.isAssignableFrom(cls)) {
        return Serializers.CharsetSerializer.class;
      } else if (cls.isArray()) {
        Preconditions.checkArgument(!cls.getComponentType().isPrimitive());
        return ArraySerializers.ObjectArraySerializer.class;
      } else if (Functions.isLambda(cls)) {
        return LambdaSerializer.class;
      } else if (ReflectionUtils.isJdkProxy(cls)) {
        return JdkProxySerializer.class;
      } else if (Calendar.class.isAssignableFrom(cls)) {
        return TimeSerializers.CalendarSerializer.class;
      } else if (ZoneId.class.isAssignableFrom(cls)) {
        return TimeSerializers.ZoneIdSerializer.class;
      } else if (TimeZone.class.isAssignableFrom(cls)) {
        return TimeSerializers.TimeZoneSerializer.class;
      } else if (Externalizable.class.isAssignableFrom(cls)) {
        return ExternalizableSerializer.class;
      } else if (ImmutableList.class.isAssignableFrom(cls)) {
        return CollectionSerializers.ImmutableListSerializer.class;
      } else if (ImmutableMap.class.isAssignableFrom(cls)) {
        return MapSerializers.ImmutableMapSerializer.class;
      } else if (ByteBuffer.class.isAssignableFrom(cls)) {
        return BufferSerializers.ByteBufferSerializer.class;
      }
      if (fury.getConfig().checkJdkClassSerializable()) {
        if (cls.getName().startsWith("java") && !(Serializable.class.isAssignableFrom(cls))) {
          throw new UnsupportedOperationException(
              String.format("Class %s doesn't support serialization.", cls));
        }
      }
      if (Collection.class.isAssignableFrom(cls)) {
        // Serializer of common collection such as ArrayList/LinkedList should be registered
        // already.
        Class<? extends Serializer> serializerClass =
            ChildContainerSerializers.getCollectionSerializerClass(cls);
        if (serializerClass != null) {
          return serializerClass;
        }
        if (requireJavaSerialization(cls) || useReplaceResolveSerializer(cls)) {
          return CollectionSerializers.JDKCompatibleCollectionSerializer.class;
        }
        if (fury.getLanguage() == Language.JAVA) {
          return CollectionSerializers.DefaultJavaCollectionSerializer.class;
        } else {
          return CollectionSerializers.CollectionSerializer.class;
        }
      } else if (Map.class.isAssignableFrom(cls)) {
        // Serializer of common map such as HashMap/LinkedHashMap should be registered already.
        Class<? extends Serializer> serializerClass =
            ChildContainerSerializers.getMapSerializerClass(cls);
        if (serializerClass != null) {
          return serializerClass;
        }
        if (requireJavaSerialization(cls) || useReplaceResolveSerializer(cls)) {
          return MapSerializers.JDKCompatibleMapSerializer.class;
        }
        if (fury.getLanguage() == Language.JAVA) {
          return MapSerializers.DefaultJavaMapSerializer.class;
        } else {
          return MapSerializers.MapSerializer.class;
        }
      }
      if (useReplaceResolveSerializer(cls)) {
        return ReplaceResolveSerializer.class;
      }
      if (requireJavaSerialization(cls)) {
        return getJavaSerializer(cls);
      }
      Class<?> clz = cls;
      return getObjectSerializerClass(
          cls,
          metaContextShareEnabled,
          codegen,
          new JITContext.SerializerJITCallback<Class<? extends Serializer>>() {
            @Override
            public void onSuccess(Class<? extends Serializer> result) {
              setSerializer(clz, Serializers.newSerializer(fury, clz, result));
              if (classInfoCache.cls == clz) {
                classInfoCache = NIL_CLASS_INFO; // clear class info cache
              }
              Preconditions.checkState(getSerializer(clz).getClass() == result);
            }

            @Override
            public Object id() {
              return clz;
            }
          });
    }
  }

  public Class<? extends Serializer> getObjectSerializerClass(
      Class<?> cls, JITContext.SerializerJITCallback<Class<? extends Serializer>> callback) {
    boolean codegen =
        supportCodegenForJavaSerialization(cls) && fury.getConfig().isCodeGenEnabled();
    return getObjectSerializerClass(cls, false, codegen, callback);
  }

  private Class<? extends Serializer> getObjectSerializerClass(
      Class<?> cls,
      boolean shareMeta,
      boolean codegen,
      JITContext.SerializerJITCallback<Class<? extends Serializer>> callback) {
    if (fury.getLanguage() != Language.JAVA) {
      LOG.warn("Class {} isn't supported for cross-language serialization.", cls);
    }
    if (codegen) {
      if (extRegistry.getClassCtx.contains(cls)) {
        // avoid potential recursive call for seq codec generation.
        return CodegenSerializer.LazyInitBeanSerializer.class;
      } else {
        extRegistry.getClassCtx.add(cls);
        Class<? extends Serializer> sc;
        switch (fury.getCompatibleMode()) {
          case SCHEMA_CONSISTENT:
            sc =
                fury.getJITContext()
                    .registerSerializerJITCallback(
                        () -> ObjectSerializer.class,
                        () -> loadCodegenSerializer(fury, cls),
                        callback);
            extRegistry.getClassCtx.remove(cls);
            return sc;
          case COMPATIBLE:
            // If share class meta, compatible serializer won't be necessary, class
            // definition will be sent to peer to create serializer for deserialization.
            sc =
                fury.getJITContext()
                    .registerSerializerJITCallback(
                        () -> shareMeta ? ObjectSerializer.class : CompatibleSerializer.class,
                        () ->
                            shareMeta
                                ? loadCodegenSerializer(fury, cls)
                                : loadCompatibleCodegenSerializer(fury, cls),
                        callback);
            extRegistry.getClassCtx.remove(cls);
            return sc;
          default:
            throw new UnsupportedOperationException(
                String.format("Unsupported mode %s", fury.getCompatibleMode()));
        }
      }
    } else {
      LOG.debug("Object of type {} can't be serialized by jit", cls);
      switch (fury.getCompatibleMode()) {
        case SCHEMA_CONSISTENT:
          return ObjectSerializer.class;
        case COMPATIBLE:
          return shareMeta ? ObjectSerializer.class : CompatibleSerializer.class;
        default:
          throw new UnsupportedOperationException(
              String.format("Unsupported mode %s", fury.getCompatibleMode()));
      }
    }
  }

  public Class<? extends Serializer> getJavaSerializer(Class<?> clz) {
    if (Collection.class.isAssignableFrom(clz)) {
      return CollectionSerializers.JDKCompatibleCollectionSerializer.class;
    } else if (Map.class.isAssignableFrom(clz)) {
      return MapSerializers.JDKCompatibleMapSerializer.class;
    } else {
      if (useReplaceResolveSerializer(clz)) {
        return ReplaceResolveSerializer.class;
      }
      return fury.getDefaultJDKStreamSerializerType();
    }
  }

  public FieldResolver getFieldResolver(Class<?> cls) {
    // can't use computeIfAbsent, since there may be recursive muiltple
    // `getFieldResolver` thus multiple updates, which cause concurrent
    // modification exeption.
    FieldResolver fieldResolver = extRegistry.fieldResolverMap.get(cls);
    if (fieldResolver == null) {
      fieldResolver = FieldResolver.of(fury, cls);
      extRegistry.fieldResolverMap.put(cls, fieldResolver);
    }
    return fieldResolver;
  }

  // thread safe
  public SortedMap<Field, Descriptor> getAllDescriptorsMap(Class<?> clz, boolean searchParent) {
    // when jit thread query this, it is already built by serialization main thread.
    return extRegistry.descriptorsCache.computeIfAbsent(
        Tuple2.of(clz, searchParent), t -> Descriptor.getAllDescriptorsMap(clz, searchParent));
  }

  /**
   * Whether to track reference for this type. If false, reference tracing of subclasses may be
   * ignored too.
   */
  public boolean needToWriteReference(Class<?> cls) {
    if (fury.trackingReference()) {
      ClassInfo classInfo = getClassInfo(cls, false);
      if (classInfo == null || classInfo.serializer == null) {
        // TODO group related logic together for extendability and consistency.
        return !cls.isEnum();
      } else {
        return classInfo.serializer.needToWriteReference();
      }
    }
    return false;
  }

  public ClassInfo getClassInfo(short classId) {
    ClassInfo classInfo = registeredId2ClassInfo[classId];
    if (classInfo.serializer == null) {
      addSerializer(classInfo.cls, createSerializer(classInfo.cls));
      classInfo = classInfoMap.get(classInfo.cls);
    }
    return classInfo;
  }

  // Invoked by fury JIT.
  public ClassInfo getClassInfo(Class<?> cls) {
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo == null || classInfo.serializer == null) {
      addSerializer(cls, createSerializer(cls));
      classInfo = classInfoMap.get(cls);
    }
    return classInfo;
  }

  public ClassInfo getClassInfo(Class<?> cls, ClassInfoCache classInfoCache) {
    ClassInfo classInfo = classInfoCache.classInfo;
    if (classInfo.getCls() != cls) {
      classInfo = classInfoMap.get(cls);
      if (classInfo == null || classInfo.serializer == null) {
        addSerializer(cls, createSerializer(cls));
        classInfo = Objects.requireNonNull(classInfoMap.get(cls));
      }
      classInfoCache.classInfo = classInfo;
    }
    assert classInfo.serializer != null;
    return classInfo;
  }

  /**
   * Get class information, create class info if not found and `createClassInfoIfNotFound` is true.
   *
   * @param cls which class to get class info.
   * @param createClassInfoIfNotFound whether create class info if not found.
   * @return Class info.
   */
  public ClassInfo getClassInfo(Class<?> cls, boolean createClassInfoIfNotFound) {
    if (createClassInfoIfNotFound) {
      return getOrUpdateClassInfo(cls);
    }
    if (extRegistry.getClassCtx.contains(cls)) {
      return null;
    } else {
      return classInfoMap.get(cls);
    }
  }

  @Internal
  public ClassInfo getOrUpdateClassInfo(Class<?> cls) {
    ClassInfo classInfo = classInfoCache;
    if (classInfo.cls != cls) {
      classInfo = classInfoMap.get(cls);
      if (classInfo == null || classInfo.serializer == null) {
        addSerializer(cls, createSerializer(cls));
        classInfo = classInfoMap.get(cls);
      }
      classInfoCache = classInfo;
    }
    return classInfo;
  }

  private ClassInfo getOrUpdateClassInfo(short classId) {
    ClassInfo classInfo = classInfoCache;
    if (classInfo.classId != classId) {
      classInfo = registeredId2ClassInfo[classId];
      if (classInfo.serializer == null) {
        addSerializer(classInfo.cls, createSerializer(classInfo.cls));
        classInfo = classInfoMap.get(classInfo.cls);
      }
      classInfoCache = classInfo;
    }
    return classInfo;
  }

  public <T> Serializer<T> createSerializerSafe(Class<T> cls, Supplier<Serializer<T>> func) {
    Serializer serializer = fury.getClassResolver().getSerializer(cls, false);
    try {
      return func.get();
    } catch (Throwable t) {
      // Some serializer may set itself in constructor as serializer, but the
      // constructor failed later. For example, some final type field doesn't
      // support serialization.
      resetSerializer(cls, serializer);
      Platform.throwException(t);
      throw new IllegalStateException("unreachable");
    }
  }

  private Serializer createSerializer(Class<?> cls) {
    if (!extRegistry.registeredClassIdMap.containsKey(cls)) {
      String msg =
          String.format(
              "%s is not registered, if it's not the type you want to serialize, "
                  + "it may be a **vulnerability**. If it's not a vulnerability, "
                  + "registering class by `Fury#register` will have better performance, "
                  + "otherwise class name will be serialized too.",
              cls);
      boolean forbidden = BlackList.getDefaultBlackList().contains(cls.getName());
      if (forbidden
          || (fury.isClassRegistrationRequired()
              && !isSecure(extRegistry.registeredClassIdMap, cls))) {
        throw new InsecureException(msg);
      } else {
        if (!Functions.isLambda(cls) && !ReflectionUtils.isJdkProxy(cls)) {
          LOG.warn(msg);
        }
      }
    }
    if (extRegistry.serializerFactory != null) {
      Serializer serializer = extRegistry.serializerFactory.createSerializer(fury, cls);
      if (serializer != null) {
        return serializer;
      }
    }
    Class<? extends Serializer> serializerClass = getSerializerClass(cls);
    return Serializers.newSerializer(fury, cls, serializerClass);
  }

  private static boolean isSecure(IdentityMap<Class<?>, Short> registeredClasses, Class<?> cls) {
    if (BlackList.getDefaultBlackList().contains(cls.getName())) {
      return false;
    }
    if (registeredClasses.containsKey(cls)) {
      return true;
    }
    if (cls.isArray()) {
      return isSecure(registeredClasses, TypeUtils.getArrayComponent(cls));
    }
    // Don't take java Exception as secure in case future JDK introduce insecure JDK exception.
    // if (Exception.class.isAssignableFrom(cls)
    //     && cls.getName().startsWith("java.")
    //     && !cls.getName().startsWith("java.sql")) {
    //   return true;
    // }
    return Functions.isLambda(cls) || ReflectionUtils.isJdkProxy(cls);
  }

  /**
   * Write class info to <code>buffer</code>. TODO(chaokunyang): The method should try to write
   * aligned data to reduce cpu instruction overhead. `writeClass` is the last step before
   * serializing object, if this writes are aligned, then later serialization will be more
   * efficient.
   */
  public void writeClassAndUpdateCache(MemoryBuffer buffer, Class<?> cls) {
    // fast path for common type
    if (cls == Long.class) {
      buffer.writeByte(USE_STRING_ID);
      buffer.writeShort(LONG_CLASS_ID);
    } else if (cls == Integer.class) {
      buffer.writeByte(USE_STRING_ID);
      buffer.writeShort(INTEGER_CLASS_ID);
    } else if (cls == Double.class) {
      buffer.writeByte(USE_STRING_ID);
      buffer.writeShort(DOUBLE_CLASS_ID);
    } else {
      writeClass(buffer, getOrUpdateClassInfo(cls));
    }
  }

  // The jit-compiled native code fot this method will be too big for inline, so we generated
  // `getClassInfo`
  // in fury-jit, see `BaseSeqCodecBuilder#writeAndGetClassInfo`
  // public ClassInfo writeClass(MemoryBuffer buffer, Class<?> cls, ClassInfoCache classInfoCache) {
  //   ClassInfo classInfo = getClassInfo(cls, classInfoCache);
  //   writeClass(buffer, classInfo);
  //   return classInfo;
  // }

  /** Write classname for java serialization. */
  public void writeClass(MemoryBuffer buffer, ClassInfo classInfo) {
    if (classInfo.classId == NO_CLASS_ID) { // no class id provided.
      // use classname
      buffer.writeByte(USE_CLASS_VALUE);
      if (metaContextShareEnabled) {
        // FIXME(chaokunyang) Register class but not register serializer can't be used with
        //  meta share mode, because no class def are sent to peer.
        writeClassWithMetaShare(buffer, classInfo);
      } else {
        // if it's null, it's a bug.
        assert classInfo.packageNameBytes != null;
        enumStringResolver.writeEnumStringBytes(buffer, classInfo.packageNameBytes);
        assert classInfo.classNameBytes != null;
        enumStringResolver.writeEnumStringBytes(buffer, classInfo.classNameBytes);
      }
    } else {
      // use classId
      int writerIndex = buffer.writerIndex();
      buffer.increaseWriterIndex(3);
      buffer.unsafePut(writerIndex, USE_STRING_ID);
      buffer.unsafePutShort(writerIndex + 1, classInfo.classId);
    }
  }

  public void writeClassWithMetaShare(MemoryBuffer buffer, ClassInfo classInfo) {
    MetaContext metaContext = fury.getSerializationContext().getMetaContext();
    Preconditions.checkNotNull(
        metaContext,
        "Meta context must be set before serialization,"
            + " please set meta context by SerializationContext.setMetaContext");
    IdentityObjectIntMap<Class<?>> classMap = metaContext.classMap;
    int newId = classMap.size;
    int id = classMap.putOrGet(classInfo.cls, newId);
    if (id >= 0) {
      buffer.writePositiveVarInt(id);
    } else {
      buffer.writePositiveVarInt(newId);
      ClassDef classDef;
      Serializer<?> serializer = classInfo.serializer;
      if (fury.getConfig().getCompatibleMode() == CompatibleMode.COMPATIBLE
          && (serializer instanceof Generated.GeneratedObjectSerializer
              // May already switched to MetaSharedSerializer when update class info cache.
              || serializer instanceof Generated.GeneratedMetaSharedSerializer
              || serializer instanceof CodegenSerializer.LazyInitBeanSerializer
              || serializer instanceof ObjectSerializer
              || serializer instanceof MetaSharedSerializer)) {
        classDef =
            classDefMap.computeIfAbsent(classInfo.cls, cls -> ClassDef.buildClassDef(cls, fury));
      } else {
        classDef =
            classDefMap.computeIfAbsent(
                classInfo.cls,
                cls ->
                    ClassDef.buildClassDef(
                        this,
                        cls,
                        new ArrayList<>(),
                        ImmutableMap.of(META_SHARE_FIELDS_INFO_KEY, "false")));
      }
      metaContext.writingClassDefs.add(classDef);
    }
  }

  private Class<?> readClassWithMetaShare(MemoryBuffer buffer) {
    MetaContext metaContext = fury.getSerializationContext().getMetaContext();
    Preconditions.checkNotNull(
        metaContext,
        "Meta context must be set before serialization,"
            + " please set meta context by SerializationContext.setMetaContext");
    int id = buffer.readPositiveVarInt();
    List<ClassInfo> readClassInfos = metaContext.readClassInfos;
    ClassInfo classInfo = readClassInfos.get(id);
    if (classInfo == null) {
      List<ClassDef> readClassDefs = metaContext.readClassDefs;
      ClassDef classDef = readClassDefs.get(id);
      Class<?> cls = loadClass(classDef.getClassName());
      classInfo = getClassInfo(cls, false);
      if (classInfo == null) {
        Short classId = extRegistry.registeredClassIdMap.get(cls);
        classInfo = new ClassInfo(this, cls, null, null, classId == null ? NO_CLASS_ID : classId);
        classInfoMap.put(cls, classInfo);
      }
      readClassInfos.set(id, classInfo);
    }
    return classInfo.cls;
  }

  private ClassInfo readClassInfoWithMetaShare(MemoryBuffer buffer, MetaContext metaContext) {
    Preconditions.checkNotNull(
        metaContext,
        "Meta context must be set before serialization,"
            + " please set meta context by SerializationContext.setMetaContext");
    int id = buffer.readPositiveVarInt();
    List<ClassInfo> readClassInfos = metaContext.readClassInfos;
    ClassInfo classInfo = readClassInfos.get(id);
    if (classInfo == null) {
      List<ClassDef> readClassDefs = metaContext.readClassDefs;
      ClassDef classDef = readClassDefs.get(id);
      if ("false".equals(classDef.getExtMeta().getOrDefault(META_SHARE_FIELDS_INFO_KEY, ""))) {
        Class<?> cls = loadClass(classDef.getClassName());
        classInfo = getClassInfo(cls);
      } else {
        Tuple2<ClassDef, ClassInfo> classDefTuple = extRegistry.classIdToDef.get(classDef.getId());
        if (classDefTuple == null || classDefTuple.f1 == null) {
          if (classDefTuple != null) {
            classDef = classDefTuple.f0;
          }
          Class<?> cls = loadClass(classDef.getClassName());
          classInfo = getMetaSharedClassInfo(classDef, cls);
          // Share serializer for same version class def to avoid too much different meta
          // context take up too much memory.
          extRegistry.classIdToDef.put(classDef.getId(), Tuple2.of(classDef, classInfo));
        } else {
          classInfo = classDefTuple.f1;
        }
      }
      readClassInfos.set(id, classInfo);
    }
    return classInfo;
  }

  // TODO(chaokunyang) if ClassDef is consistent with class in this process,
  //  use existing serializer instead.
  private ClassInfo getMetaSharedClassInfo(ClassDef classDef, Class<?> clz) {
    if (clz == UnexistedSkipClass.class) {
      clz = UnexistedMetaSharedClass.class;
    }
    Class<?> cls = clz;
    Short classId = extRegistry.registeredClassIdMap.get(cls);
    ClassInfo classInfo =
        new ClassInfo(this, cls, null, null, classId == null ? NO_CLASS_ID : classId);
    if (cls == UnexistedMetaSharedClass.class) {
      classInfo.serializer = new UnexistedClassSerializer(fury, classDef);
      // ensure `UnExistedMetaSharedClass` registered to write fixed-length class def,
      // so we can rewrite it in `UnExistedClassSerializer`.
      Preconditions.checkNotNull(classId);
      return classInfo;
    }
    Class<? extends Serializer> sc =
        fury.getJITContext()
            .registerSerializerJITCallback(
                () -> MetaSharedSerializer.class,
                () -> CodecUtils.loadOrGenMetaSharedCodecClass(fury, cls, classDef),
                c -> classInfo.serializer = Serializers.newSerializer(fury, cls, c));
    if (sc == MetaSharedSerializer.class) {
      classInfo.serializer = new MetaSharedSerializer(fury, cls, classDef);
    } else {
      classInfo.serializer = Serializers.newSerializer(fury, cls, sc);
    }
    return classInfo;
  }

  /**
   * Write all new class definitions meta to buffer at last, so that if some class doesn't exist on
   * peer, but one of class which exists on both side are sent in this stream, the definition meta
   * can still be stored in peer, and can be resolved next time when sent only an id.
   */
  public void writeClassDefs(MemoryBuffer buffer) {
    MetaContext metaContext = fury.getSerializationContext().getMetaContext();
    buffer.writePositiveVarInt(metaContext.writingClassDefs.size());
    for (ClassDef classDef : metaContext.writingClassDefs) {
      classDef.writeClassDef(buffer);
    }
    metaContext.writingClassDefs.clear();
  }

  /**
   * Ensure all class definition are read and populated, even there are deserialization exception
   * such as ClassNotFound. So next time a class def written previously identified by an id can be
   * got from the meta context.
   */
  public void readClassDefs(MemoryBuffer buffer) {
    MetaContext metaContext = fury.getSerializationContext().getMetaContext();
    int classDefOffset = buffer.readInt();
    int readerIndex = buffer.readerIndex();
    buffer.readerIndex(classDefOffset);
    int numClassDefs = buffer.readPositiveVarInt();
    for (int i = 0; i < numClassDefs; i++) {
      ClassDef readClassDef = ClassDef.readClassDef(buffer);
      // Share same class def to reduce memory footprint, since there may be many meta context.
      ClassDef classDef =
          extRegistry.classIdToDef.computeIfAbsent(
                  readClassDef.getId(), key -> Tuple2.of(readClassDef, null))
              .f0;
      metaContext.readClassDefs.add(classDef);
      // Will be set lazily, so even some classes doesn't exist, remaining classinfo
      // can be created still.
      metaContext.readClassInfos.add(null);
    }
    buffer.readerIndex(readerIndex);
  }

  /**
   * Native code for ClassResolver.writeClass is too big to inline, so inline it manually.
   *
   * <p>See `already compiled into a big method` in <a
   * href="https://wiki.openjdk.org/display/HotSpot/Server+Compiler+Inlining+Messages">Server+Compiler+Inlining+Messages</a>
   */
  // Note: Thread safe fot jit thread to call.
  public Expression writeClassExpr(
      Expression classResolverRef, Expression buffer, Expression classInfo) {
    Expression classId = new Expression.Invoke(classInfo, "getClassId", PRIMITIVE_SHORT_TYPE);
    Expression.ListExpression writeUnregistered =
        new Expression.ListExpression(
            new Expression.Invoke(buffer, "writeByte", Expression.Literal.ofByte(USE_CLASS_VALUE)));
    if (metaContextShareEnabled) {
      writeUnregistered.add(
          new Expression.Invoke(classResolverRef, "writeClassWithMetaShare", buffer, classInfo));
    } else {
      writeUnregistered.add(
          new Expression.Invoke(
              classResolverRef,
              "writeEnumStringBytes",
              buffer,
              inlineInvoke(classInfo, "getPackageNameBytes", TypeToken.of(EnumStringBytes.class))),
          new Expression.Invoke(
              classResolverRef,
              "writeEnumStringBytes",
              buffer,
              inlineInvoke(classInfo, "getClassNameBytes", TypeToken.of(EnumStringBytes.class))));
    }
    return new Expression.If(
        eq(classId, Expression.Literal.ofShort(NO_CLASS_ID)),
        writeUnregistered,
        writeClassExpr(buffer, classId));
  }

  // Note: Thread safe fot jit thread to call.
  public Expression writeClassExpr(Expression buffer, short classId) {
    Preconditions.checkArgument(classId != NO_CLASS_ID);
    return writeClassExpr(buffer, Expression.Literal.ofShort(classId));
  }

  // Note: Thread safe fot jit thread to call.
  public Expression writeClassExpr(Expression buffer, Expression classId) {
    Expression writerIndex = new Expression.Invoke(buffer, "writerIndex", PRIMITIVE_INT_TYPE);
    return new Expression.ListExpression(
        writerIndex,
        new Expression.Invoke(buffer, "increaseWriterIndex", Expression.Literal.ofInt(3)),
        new Expression.Invoke(
            buffer, "unsafePut", writerIndex, Expression.Literal.ofByte(USE_STRING_ID)),
        new Expression.Invoke(
            buffer,
            "unsafePutShort",
            ExpressionUtils.add(writerIndex, Expression.Literal.ofInt(1)),
            classId));
  }

  // Invoked by Fury JIT.
  public void writeEnumStringBytes(MemoryBuffer buffer, EnumStringBytes byteString) {
    enumStringResolver.writeEnumStringBytes(buffer, byteString);
  }

  // Note: Thread safe fot jit thread to call.
  public Expression skipRegisteredClassExpr(Expression buffer) {
    return new Expression.Invoke(buffer, "increaseReaderIndex", Expression.Literal.ofInt(3));
  }

  /**
   * Write classname for java serialization. Note that the object of provided class can be
   * non-serializable, and class with writeReplace/readResolve defined won't be skipped. For
   * serializable object, {@link #writeClass(MemoryBuffer, ClassInfo)} should be invoked.
   */
  public void writeClassInternal(MemoryBuffer buffer, Class<?> cls) {
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo == null) {
      Short classId = extRegistry.registeredClassIdMap.get(cls);
      // Don't create serializer in case the object for class is non-serializable,
      // Or class is abstract or interface.
      classInfo = new ClassInfo(this, cls, null, null, classId == null ? NO_CLASS_ID : classId);
      classInfoMap.put(cls, classInfo);
    }
    short classId = classInfo.classId;
    if (classId == REPLACE_STUB_ID) {
      // clear class id to avoid replaced class written as
      // ReplaceResolveSerializer.ReplaceStub
      classInfo.classId = NO_CLASS_ID;
    }
    writeClass(buffer, classInfo);
    classInfo.classId = classId;
  }

  /**
   * Read serialized java classname. Note that the object of the class can be non-serializable. For
   * serializable object, {@link #readClassAndUpdateCache(MemoryBuffer)} or {@link
   * #readClassInfo(MemoryBuffer, ClassInfoCache)} should be invoked.
   */
  public Class<?> readClassInternal(MemoryBuffer buffer) {
    if (buffer.readByte() == USE_CLASS_VALUE) {
      if (metaContextShareEnabled) {
        return readClassWithMetaShare(buffer);
      }
      EnumStringBytes packageBytes = enumStringResolver.readEnumStringBytes(buffer);
      EnumStringBytes simpleClassNameBytes = enumStringResolver.readEnumStringBytes(buffer);
      final Class<?> cls = loadBytesToClass(packageBytes, simpleClassNameBytes);
      currentReadClass = cls;
      return cls;
    } else {
      // use classId
      short classId = buffer.readShort();
      ClassInfo classInfo = registeredId2ClassInfo[classId];
      final Class<?> cls = classInfo.cls;
      currentReadClass = cls;
      return cls;
    }
  }

  public ClassInfo readAndUpdateClassInfoCache(MemoryBuffer buffer) {
    if (buffer.readByte() == USE_CLASS_VALUE) {
      ClassInfo classInfo;
      if (metaContextShareEnabled) {
        classInfo =
            readClassInfoWithMetaShare(buffer, fury.getSerializationContext().getMetaContext());
      } else {
        classInfo = readClassInfoFromBytes(buffer, classInfoCache);
      }
      classInfoCache = classInfo;
      currentReadClass = classInfo.cls;
      return classInfo;
    } else {
      // use classId
      short classId = buffer.readShort();
      ClassInfo classInfo = getOrUpdateClassInfo(classId);
      currentReadClass = classInfo.cls;
      return classInfo;
    }
  }

  /** Read class info from java data <code>buffer</code> as a Class. */
  public Class<?> readClassAndUpdateCache(MemoryBuffer buffer) {
    if (buffer.readByte() == USE_CLASS_VALUE) {
      ClassInfo classInfo;
      if (metaContextShareEnabled) {
        classInfo =
            readClassInfoWithMetaShare(buffer, fury.getSerializationContext().getMetaContext());
      } else {
        classInfo = readClassInfoFromBytes(buffer, classInfoCache);
      }
      classInfoCache = classInfo;
      currentReadClass = classInfo.cls;
      return classInfo.cls;
    } else {
      // use classId
      short classId = buffer.readShort();
      ClassInfo classInfo = getOrUpdateClassInfo(classId);
      final Class<?> cls = classInfo.cls;
      currentReadClass = cls;
      return cls;
    }
  }

  // Called by fury Java serialization JIT.
  public ClassInfo readClassInfo(MemoryBuffer buffer, ClassInfo classInfoCache) {
    if (buffer.readByte() == USE_CLASS_VALUE) {
      if (metaContextShareEnabled) {
        return readClassInfoWithMetaShare(buffer, fury.getSerializationContext().getMetaContext());
      }
      return readClassInfoFromBytes(buffer, classInfoCache);
    } else {
      // use classId
      short classId = buffer.readShort();
      return getClassInfo(classId);
    }
  }

  // Called by fury Java serialization JIT.
  public ClassInfo readClassInfo(MemoryBuffer buffer, ClassInfoCache classInfoCache) {
    if (buffer.readByte() == USE_CLASS_VALUE) {
      if (metaContextShareEnabled) {
        return readClassInfoWithMetaShare(buffer, fury.getSerializationContext().getMetaContext());
      }
      return readClassInfoFromBytes(buffer, classInfoCache);
    } else {
      // use classId
      short classId = buffer.readShort();
      return getClassInfo(classId);
    }
  }

  private ClassInfo readClassInfoFromBytes(MemoryBuffer buffer, ClassInfoCache classInfoCache) {
    ClassInfo classInfo = readClassInfoFromBytes(buffer, classInfoCache.classInfo);
    classInfoCache.classInfo = classInfo;
    return classInfo;
  }

  private ClassInfo readClassInfoFromBytes(MemoryBuffer buffer, ClassInfo classInfoCache) {
    EnumStringBytes simpleClassNameBytesCache = classInfoCache.classNameBytes;
    if (simpleClassNameBytesCache != null) {
      EnumStringBytes packageNameBytesCache = classInfoCache.packageNameBytes;
      EnumStringBytes packageBytes =
          enumStringResolver.readEnumStringBytes(buffer, packageNameBytesCache);
      assert packageNameBytesCache != null;
      EnumStringBytes simpleClassNameBytes =
          enumStringResolver.readEnumStringBytes(buffer, simpleClassNameBytesCache);
      if (simpleClassNameBytesCache.hashCode == simpleClassNameBytes.hashCode
          && packageNameBytesCache.hashCode == packageBytes.hashCode) {
        return classInfoCache;
      } else {
        Class<?> cls = loadBytesToClass(packageBytes, simpleClassNameBytes);
        return getClassInfo(cls);
      }
    } else {
      EnumStringBytes packageBytes = enumStringResolver.readEnumStringBytes(buffer);
      EnumStringBytes simpleClassNameBytes = enumStringResolver.readEnumStringBytes(buffer);
      Class<?> cls = loadBytesToClass(packageBytes, simpleClassNameBytes);
      return getClassInfo(cls);
    }
  }

  private Class<?> loadBytesToClass(
      EnumStringBytes packageBytes, EnumStringBytes simpleClassNameBytes) {
    ClassNameBytes classNameBytes =
        new ClassNameBytes(packageBytes.hashCode, simpleClassNameBytes.hashCode);
    Class<?> cls = compositeClassNameBytes2Class.get(classNameBytes);
    if (cls == null) {
      String packageName = new String(packageBytes.bytes, StandardCharsets.UTF_8);
      String className = new String(simpleClassNameBytes.bytes, StandardCharsets.UTF_8);
      String entireClassName;
      if (StringUtils.isBlank(packageName)) {
        entireClassName = className;
      } else {
        entireClassName = packageName + "." + className;
      }
      cls = loadClass(entireClassName);
      compositeClassNameBytes2Class.put(classNameBytes, cls);
    }
    return cls;
  }

  public void crossLanguageWriteClass(MemoryBuffer buffer, Class<?> cls) {
    enumStringResolver.writeEnumStringBytes(buffer, getOrUpdateClassInfo(cls).fullClassNameBytes);
  }

  public void crossLanguageWriteTypeTag(MemoryBuffer buffer, Class<?> cls) {
    enumStringResolver.writeEnumStringBytes(buffer, getOrUpdateClassInfo(cls).typeTagBytes);
  }

  public Class<?> crossLanguageReadClass(MemoryBuffer buffer) {
    EnumStringBytes byteString = enumStringResolver.readEnumStringBytes(buffer);
    Class<?> cls = classNameBytes2Class.get(byteString);
    if (cls == null) {
      Preconditions.checkNotNull(byteString);
      String className = new String(byteString.bytes, StandardCharsets.UTF_8);
      cls = loadClass(className);
      classNameBytes2Class.put(byteString, cls);
    }
    currentReadClass = cls;
    return cls;
  }

  public String crossLanguageReadClassName(MemoryBuffer buffer) {
    return enumStringResolver.readEnumString(buffer);
  }

  public Class<?> getCurrentReadClass() {
    return currentReadClass;
  }

  private Class<?> loadClass(String className) {
    try {
      return Class.forName(className, false, fury.getClassLoader());
    } catch (ClassNotFoundException e) {
      try {
        return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
      } catch (ClassNotFoundException ex) {
        String msg =
            String.format(
                "Class %s not found from classloaders [%s, %s]",
                className, fury.getClassLoader(), Thread.currentThread().getContextClassLoader());
        if (fury.getConfig().isDeserializeUnExistClassEnabled()) {
          // ex.printStackTrace();
          LOG.error(msg, e);
          // FIXME create a subclass dynamically may be better?
          return UnexistedSkipClass.class;
        }
        throw new IllegalStateException(msg, ex);
      }
    }
  }

  public void reset() {
    resetRead();
    resetWrite();
  }

  public void resetRead() {}

  public void resetWrite() {}

  public Class<?> getClassByTypeId(short typeId) {
    return typeIdToClassXLangMap.get(typeId);
  }

  public Class<?> readClassByTypeTag(MemoryBuffer buffer) {
    String tag = enumStringResolver.readEnumString(buffer);
    return typeTagToClassXLangMap.get(tag);
  }

  private static class ClassNameBytes {
    private final long packageHash;
    private final long classNameHash;

    private ClassNameBytes(long packageHash, long classNameHash) {
      this.packageHash = packageHash;
      this.classNameHash = classNameHash;
    }

    @Override
    public boolean equals(Object o) {
      // ClassNameBytes is used internally, skip
      ClassNameBytes that = (ClassNameBytes) o;
      return packageHash == that.packageHash && classNameHash == that.classNameHash;
    }

    @Override
    public int hashCode() {
      int result = 31 + (int) (packageHash ^ (packageHash >>> 32));
      result = result * 31 + (int) (classNameHash ^ (classNameHash >>> 32));
      return result;
    }
  }

  public GenericType buildGenericType(TypeToken<?> typeToken) {
    return GenericType.build(
        typeToken.getType(),
        t -> {
          if (t.getClass() == Class.class) {
            return isFinal((Class<?>) t);
          } else {
            return isFinal(getRawType(t));
          }
        });
  }

  public GenericType buildGenericType(Type type) {
    return GenericType.build(
        type,
        t -> {
          if (t.getClass() == Class.class) {
            return isFinal((Class<?>) t);
          } else {
            return isFinal(getRawType(t));
          }
        });
  }

  public ClassInfo newClassInfo(Class<?> cls, Serializer<?> serializer, short classId) {
    return new ClassInfo(this, cls, null, serializer, classId);
  }

  // Invoked by fury JIT.
  public ClassInfo nilClassInfo() {
    return new ClassInfo(this, null, null, null, NO_CLASS_ID);
  }

  public ClassInfoCache nilClassInfoCache() {
    return new ClassInfoCache(nilClassInfo());
  }

  public boolean isPrimitive(short classId) {
    return classId >= PRIMITIVE_VOID_CLASS_ID && classId <= PRIMITIVE_DOUBLE_CLASS_ID;
  }

  public EnumStringResolver getEnumStringResolver() {
    return enumStringResolver;
  }

  public Fury getFury() {
    return fury;
  }
}
