// Copyright 2021 Code Intelligence GmbH
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.code_intelligence.jazzer.autofuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import net.jodah.typetools.TypeResolver;
import net.jodah.typetools.TypeResolver.Unknown;

public class Meta {
  static WeakHashMap<Class<?>, List<Class<?>>> cache = new WeakHashMap<>();

  public static Object autofuzz(FuzzedDataProvider data, Method method) {
    if (Modifier.isStatic(method.getModifiers())) {
      return autofuzz(data, method, null);
    } else {
      return autofuzz(data, method, consume(data, method.getDeclaringClass()));
    }
  }

  public static Object autofuzz(FuzzedDataProvider data, Method method, Object thisObject) {
    Object[] arguments = consumeArguments(data, method);
    try {
      return method.invoke(thisObject, arguments);
    } catch (IllegalAccessException | IllegalArgumentException | NullPointerException e) {
      // We should ensure that the arguments fed into the method are always valid.
      throw new AutofuzzError(e);
    } catch (InvocationTargetException e) {
      throw new AutofuzzInvocationException(e.getCause());
    }
  }

  public static <R> R autofuzz(FuzzedDataProvider data, Constructor<R> constructor) {
    Object[] arguments = consumeArguments(data, constructor);
    try {
      return constructor.newInstance(arguments);
    } catch (InstantiationException | IllegalAccessException | IllegalArgumentException e) {
      // This should never be reached as the logic in consume should prevent us from e.g. calling
      // constructors of abstract classes or private constructors.
      throw new AutofuzzError(e);
    } catch (InvocationTargetException e) {
      throw new AutofuzzInvocationException(e.getCause());
    }
  }

  @SuppressWarnings("unchecked")
  public static <T1> void autofuzz(FuzzedDataProvider data, Consumer1<T1> func) {
    Class<?>[] types = TypeResolver.resolveRawArguments(Consumer1.class, func.getClass());
    func.accept((T1) consumeChecked(data, types, 0));
  }

  @SuppressWarnings("unchecked")
  public static <T1, R> R autofuzz(FuzzedDataProvider data, Function1<T1, R> func) {
    Class<?>[] types = TypeResolver.resolveRawArguments(Function1.class, func.getClass());
    return func.apply((T1) consumeChecked(data, types, 0));
  }

  @SuppressWarnings("unchecked")
  public static <T1, T2, R> R autofuzz(FuzzedDataProvider data, Function2<T1, T2, R> func) {
    Class<?>[] types = TypeResolver.resolveRawArguments(Function2.class, func.getClass());
    return func.apply((T1) consumeChecked(data, types, 0), (T2) consumeChecked(data, types, 1));
  }

  public static Object consume(FuzzedDataProvider data, Class<?> type) {
    if (type == byte.class || type == Byte.class) {
      return data.consumeByte();
    } else if (type == short.class || type == Short.class) {
      return data.consumeShort();
    } else if (type == int.class || type == Integer.class) {
      return data.consumeInt();
    } else if (type == long.class || type == Long.class) {
      return data.consumeLong();
    } else if (type == float.class || type == Float.class) {
      return data.consumeFloat();
    } else if (type == double.class || type == Double.class) {
      return data.consumeDouble();
    } else if (type == boolean.class || type == Boolean.class) {
      return data.consumeBoolean();
    } else if (type == char.class || type == Character.class) {
      return data.consumeChar();
    } else if (type.isAssignableFrom(String.class)) {
      return data.consumeString(data.remainingBytes() / 2);
    } else if (type.isArray()) {
      if (type == byte[].class) {
        return data.consumeBytes(data.remainingBytes() / 2);
      } else if (type == int[].class) {
        return data.consumeInts(data.remainingBytes() / 2);
      } else if (type == short[].class) {
        return data.consumeShorts(data.remainingBytes() / 2);
      } else if (type == long[].class) {
        return data.consumeLongs(data.remainingBytes() / 2);
      } else if (type == boolean[].class) {
        return data.consumeBooleans(data.remainingBytes() / 2);
      } else {
        Object array = Array.newInstance(type.getComponentType(), data.remainingBytes() / 2);
        for (int i = 0; i < Array.getLength(array); i++) {
          Array.set(array, i, consume(data, type.getComponentType()));
        }
        return array;
      }
    } else if (type.isAssignableFrom(ByteArrayInputStream.class)) {
      return new ByteArrayInputStream(data.consumeBytes(data.remainingBytes() / 2));
    } else if (type.isEnum()) {
      return data.pickValue(type.getEnumConstants());
    } else if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
      List<Class<?>> implementingClasses = cache.get(type);
      if (implementingClasses == null) {
        try (ScanResult result =
                 new ClassGraph().enableClassInfo().enableInterClassDependencies().scan()) {
          ClassInfoList children =
              type.isInterface() ? result.getClassesImplementing(type) : result.getSubclasses(type);
          implementingClasses =
              children.getStandardClasses().filter(cls -> !cls.isAbstract()).loadClasses();
          cache.put(type, implementingClasses);
        }
      }
      return consume(data, data.pickValue(implementingClasses));
    } else if (type.getConstructors().length > 0) {
      return autofuzz(data, data.pickValue(type.getConstructors()));
    } else if (getNestedBuilderClasses(type).size() > 0) {
      List<Class<?>> nestedBuilderClasses = getNestedBuilderClasses(type);
      Class<?> pickedBuilder = data.pickValue(nestedBuilderClasses);

      List<Method> cascadingBuilderMethods = Arrays.stream(pickedBuilder.getMethods())
                                                 .filter(m -> m.getReturnType() == pickedBuilder)
                                                 .collect(Collectors.toList());

      List<Method> originalObjectCreationMethods = Arrays.stream(pickedBuilder.getMethods())
                                                       .filter(m -> m.getReturnType() == type)
                                                       .collect(Collectors.toList());

      int pickedMethodsNumber = data.consumeInt(0, cascadingBuilderMethods.size());
      List<Method> pickedMethods = new ArrayList<>();
      for (int i = 0; i < pickedMethodsNumber; i++) {
        Method method = data.pickValue(cascadingBuilderMethods);
        pickedMethods.add(method);
        cascadingBuilderMethods.remove(method);
      }

      Method builderMethod = data.pickValue(originalObjectCreationMethods);

      Object obj = autofuzz(data, data.pickValue(pickedBuilder.getConstructors()));
      for (Method method : pickedMethods) {
        obj = autofuzz(data, method, obj);
      }

      try {
        return builderMethod.invoke(obj);
      } catch (Exception e) {
        throw new AutofuzzConstructionException(e);
      }
    }
    return null;
  }

  private static List<Class<?>> getNestedBuilderClasses(Class<?> type) {
    return Arrays.stream(type.getClasses())
        .filter(cls -> cls.getName().endsWith("Builder"))
        .collect(Collectors.toList());
  }

  private static Object[] consumeArguments(FuzzedDataProvider data, Executable executable) {
    Object[] result;
    try {
      result = Arrays.stream(executable.getParameterTypes())
                   .map((type) -> consume(data, type))
                   .toArray();
      return result;
    } catch (AutofuzzConstructionException e) {
      // Do not nest AutofuzzConstructionExceptions.
      throw e;
    } catch (AutofuzzInvocationException e) {
      // If an invocation fails while creating the arguments for another invocation, the exception
      // should not be reported, so we rewrap it.
      throw new AutofuzzConstructionException(e.getCause());
    } catch (Throwable t) {
      throw new AutofuzzConstructionException(t);
    }
  }

  private static Object consumeChecked(FuzzedDataProvider data, Class<?>[] types, int i) {
    if (types[i] == Unknown.class) {
      throw new AutofuzzError("Failed to determine type of argument " + (i + 1));
    }
    Object result;
    try {
      result = consume(data, types[i]);
    } catch (AutofuzzConstructionException e) {
      // Do not nest AutofuzzConstructionExceptions.
      throw e;
    } catch (AutofuzzInvocationException e) {
      // If an invocation fails while creating the arguments for another invocation, the exception
      // should not be reported, so we rewrap it.
      throw new AutofuzzConstructionException(e.getCause());
    } catch (Throwable t) {
      throw new AutofuzzConstructionException(t);
    }
    if (result != null && !types[i].isAssignableFrom(result.getClass())) {
      throw new AutofuzzError("consume returned " + result.getClass() + ", but need " + types[i]);
    }
    return result;
  }
}
