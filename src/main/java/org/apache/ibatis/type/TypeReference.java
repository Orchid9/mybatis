/**
 *    Copyright 2009-2016 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * References a generic type.
 *
 * @param <T> the referenced type
 * @since 3.1.0
 * @author Simone Tripodi
 */
public abstract class TypeReference<T> {

  private final Type rawType;

  protected TypeReference() {
    rawType = getSuperclassTypeParameter(getClass());
  }

  /**
   * 重点在于getSuperclassTypeParameter()方法中：
   * <p>
   * 　　　　第一步：通过给定参数clazz的getGenericSuperclass()方法来获取该类类型的上一级类
   * 型（直接超类，父类，即参数类类型继承的类的类型）并带有参数类型，即带泛型。如果要获取不带
   * 泛型的父类可使用getSuperclass()方法。
   * <p>
   * 　　　　第二步：判断第一步获取的类型是否是Class类的实例
   *
   * @param clazz
   * @return
   */
  Type getSuperclassTypeParameter(Class<?> clazz) {
    Type genericSuperclass = clazz.getGenericSuperclass();
    /**
     * 此处Class解释
     * 其实每一个类都是Class类的实例，Class类是对Java中类的抽象，它本身也是一个类，
     * 但它是处于普通类上一层次的类，是类的顶层抽象。从JDK文档中可获知“Instances of the class represent classes and interfaces in a running Java application.”(意为：Class的实例表示的是在一个运行的应用中的所有类和接口)
     * ，那么我们就明白了，Class类的实例就是接口与类。那么Java中有哪些不是Class类的实例呢？泛型类，不错，如果一个类是泛型类，那么他就不再是Class类的实例，为什么呢？
     *
     * 　　泛型类是Java中一种独特的存在，它一般用于传递类（更准确的说是传递类型），类似于一般方法中
     * 传递对象的概念，它不是简单的类，而是一种带有抽象概念性质的一种类，它会通过所传递的类（参数化类）来
     * 指定当前类所代表的是基于基本类型中的哪一类类型。（通过两种类型来确定具体的类型（最后这个类型表示的
     * 是泛型类型整体表达的类型））
     *
     * 　　　　第二步：如果第一步获取的类型是带泛型的类型，那么判断不成立，则会直接执行第35行代码，将
     * 该类型强转为参数化类型，使用其getActualTypeArguments()方法来获取其参数类型（泛型类型），因为该
     * 方法获取的泛型类型可能不是一个，所以返回的是一个数组，但是我们这里只会获取到一个，所以取第一个即可。
     *
     * 　　　　但是如果第一步获取的类型不带泛型，那么就会进入条件内部执行，再次判断，获
     * 取的类型是否是TypeReference类型，如果不是该类型，则有可能是多重继承导致目标类型并不是直接
     * 继承自TypeReference，那么我们通过getSuperclass()方法获取其父类，以这个类来进行递归；但如
     * 果获取到的是TypeReference类型，只是没有添加泛型，则抛出类型异常，提示丢失泛型。
     *
     * 　　　　第三步：如果第二步判断不通过，则会执行地35行代码，来获取参数类型，然后对获取的参
     * 数类型进行判断，如果该类型还是参数化类型（仍然带有泛型，即泛型嵌套的模式），那么就需要再
     * 次执行getActualTypeArguments()方法来获取其泛型类型（参数类型），最后将该类型返回（赋值给字段）
     *
     * 　　为什么只会获取两次呢？因为，通过之前的类架构我们已经明白，具体的类型处理器最多只会存在两层继承。
     */
    if (genericSuperclass instanceof Class) {
      // try to climb up the hierarchy until meet something useful
      if (TypeReference.class != genericSuperclass) {
        return getSuperclassTypeParameter(clazz.getSuperclass());
      }

      throw new TypeException("'" + getClass() + "' extends TypeReference but misses the type parameter. "
        + "Remove the extension or add a type parameter to it.");
    }

    Type rawType = ((ParameterizedType) genericSuperclass).getActualTypeArguments()[0];
    // TODO remove this when Reflector is fixed to return Types
    if (rawType instanceof ParameterizedType) {
      rawType = ((ParameterizedType) rawType).getRawType();
    }

    return rawType;
  }

  /**
   * 最后说一下，这个类型引用的目的，它就是为了持有这个具体的类型处理器所处理的Java类型的原生类型。
   * 我们可以看到在该类中还有两个方法getRawType()和toString()方法，这两个方法都是public修饰的，是
   * 对外公开的方法，那么也就意味着这个原生类型是为了被外部调用而设。
   * 通过检索发现，getRawType()方法重点被调用的地方在TypeHandlerRegistry（类型处理器注册器）中，在没
   * 有指定JavaType而只有TypeHandler的情况下，调用该TypeHandler的getRawType()方法来获取其原生类型
   * （即参数类型）来作为其JavaType来进行类型处理器的注册。
   */
  public final Type getRawType() {
    return rawType;
  }

  @Override
  public String toString() {
    return rawType.toString();
  }

}
