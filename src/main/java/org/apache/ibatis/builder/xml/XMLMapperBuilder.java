/**
 *    Copyright 2009-2020 the original author or authors.
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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {

  private final XPathParser parser;
  // mapper建造者辅助类
  private final MapperBuilderAssistant builderAssistant;
  private final Map<String, XNode> sqlFragments;
  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  public void parse() {
    // 如果不是是标签中 resource 加载的
    if (!configuration.isResourceLoaded(resource)) {
      // 配置DAO层的mapper到configuratoin
      configurationElement(parser.evalNode("/mapper"));
      configuration.addLoadedResource(resource);
      bindMapperForNamespace();
    }

    parsePendingResultMaps();
    parsePendingCacheRefs();
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  private void configurationElement(XNode context) {
    try {
      // <mapper namespace="org.apache.ibatis.submitted.substitution_in_annots.SubstitutionInAnnotsMapper">
      //    <select id="getPersonNameByIdWithXml" parameterType="int" resultType="String">
      //        select firstName from ibtest.names where id=${value}
      //    </select>
      //</mapper>
      // 获取namespace 设计概念类似于java中的package
      String namespace = context.getStringAttribute("namespace");
      if (namespace == null || namespace.isEmpty()) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      // 为Mapper辅助类设置当前加载的 namespace
      builderAssistant.setCurrentNamespace(namespace);
      // 配置cache-ref，cache-ref代表引用别的命名空间的Cache配置，两个命名空间的操作使用的是同一个Cache。
      // <mapper namespace="org.apache.ibatis.submitted.resolution.cacherefs.MapperA">
      //  <cache-ref namespace="org.apache.ibatis.submitted.resolution.cacherefs.MapperB" />
      //  <select id="getUser" resultType="org.apache.ibatis.submitted.resolution.User">
      //    select id, name from users where id = #{id}
      //  </select>
      //</mapper>
      cacheRefElement(context.evalNode("cache-ref"));
      // <mapper namespace="org.apache.ibatis.submitted.cacheorder.Mapper2">
      //    <cache/>
      // </mapper>
      // 配置cache
      // 空cache元素定义会生成一个采用最近最少使用算法最多只能存储1024个元素的缓存，而且是可读写的缓存，即该缓存是全局共享的，
      // 任何一个线程在拿到缓存结果后对数据的修改都将影响其它线程获取的缓存结果，因为它们是共享的，同一个对象。
      //cache元素可指定如下属性，每种属性的指定都是针对都是针对底层Cache的一种装饰，采用的是装饰器的模式。
      cacheElement(context.evalNode("cache"));
      // 配置parameterMap,老式风格的参数映射,没见人用过，暂不理它
      // <mapper namespace="org.apache.ibatis.submitted.xml_external_ref.ParameterMapReferencePetMapper">
      //    <parameterMap type="org.apache.ibatis.submitted.xml_external_ref.Person" id="personParameter">
      //        <parameter property="id"/>
      //    </parameterMap>
      //</mapper>
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      // 配置 resultMap 结果集
      // <mapper namespace="mapper">
      //    <resultMap id="ParentBeanResultMap" type="org.apache.ibatis.submitted.nestedresulthandler_multiple_association.ParentBean">
      //        <id property="id" column="id"/>
      //        <result property="value" column="value"/>
      //        <collection property="childs" select="selectChildsBinomes" column="id" />
      //    </resultMap>
      // </mapper>
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      // 配置 sql (定义可重用的 SQL 代码段)
      // <sql id="sql1">id,name,age,gender</sql>
      //
      //<select id="getPerson" parameterType="int" resultType="orm.Person">
      //    select
      //    <include refid="sql1"></include>
      //     from Person where id=#{id}
      //</select>
      sqlElement(context.evalNodes("/mapper/sql"));
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }

  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
  }

  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingCacheRefs() {
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolveCacheRef();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
        }
      }
    }
  }

  private void parsePendingStatements() {
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }

  private void cacheRefElement(XNode context) {
    if (context != null) {
      // 全局上下文中添加 cacheRef
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
        // 处理缓存引用
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        // 如果出现异常，将其添加到上下文中的incompleteCacheRefs（为完成的cache引用）
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  private void cacheElement(XNode context) {
    // 参考文档：https://yq.aliyun.com/articles/608941
    if (context != null) {
      // type：type属性用来指定当前底层缓存实现类，默认是PerpetualCache，如果我们想使用自定义的Cache，则可以通过该属性来指定，
      // 对应的值是我们自定义的Cache的全路径名称。
      String type = context.getStringAttribute("type", "PERPETUAL");
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      // eviction：eviction，驱逐的意思。数据淘汰策略，默认是LRU，对应的就是LruCache，其默认只保存1024个Key，超出时按照最近最少使用算法进行驱逐，
      // 详情请参考LruCache的源码。如果想使用自己的算法，则可以将该值指定为自己的驱逐算法实现类，只需要自己的类实现Mybatis的Cache接口即可。除了LRU以外，
      // 系统还提供了FIFO（先进先出，对应FifoCache）、SOFT（采用软引用存储Value，便于垃圾回收，对应SoftCache）和WEAK（采用弱引用存储Value，便于垃圾回收，
      // 对应WeakCache）这三种策略。
      String eviction = context.getStringAttribute("eviction", "LRU");
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      // flushInterval：清空缓存的时间间隔，单位是毫秒，默认是不会清空的。当指定了该值时会再用ScheduleCache包装一次，其会在每次对缓存进行操作时判断距离
      // 最近一次清空缓存的时间是否超过了flushInterval指定的时间，如果超出了，则清空当前的缓存，详情可参考ScheduleCache的实现。
      Long flushInterval = context.getLongAttribute("flushInterval");
      // size：用来指定缓存中最多保存的Key的数量。其是针对LruCache而言的，LruCache默认只存储最多1024个Key，可通过该属性来改变默认值，当然，
      // 如果你通过eviction指定了自己的驱逐算法，同时自己的实现里面也有setSize方法，那么也可以通过cache的size属性给自定义的驱逐算法里面的size赋值。
      Integer size = context.getIntAttribute("size");
      // readOnly：是否只读 ，默认为false。当指定为false时，底层会用SerializedCache包装一次，其会在写缓存的时候将缓存对象进行序列化，然后在读缓存的时候
      // 进行反序列化，这样每次读到的都将是一个新的对象，即使你更改了读取到的结果，也不会影响原来缓存的对象，即非只读，你每次拿到这个缓存结果都可以进行
      // 修改，而不会影响原来的缓存结果； 当指定为true时那就是每次获取的都是同一个引用，对其修改会影响后续的缓存数据获取，这种情况下是不建议对获取到的
      // 缓存结果进行更改，意为只读(不建议设置为true)。 这是Mybatis二级缓存读写和只读的定义，可能与我们通常情况下的只读和读写意义有点不同。每次都进行
      // 序列化和反序列化无疑会影响性能，但是这样的缓存结果更安全，不会被随意更改，具体可根据实际情况进行选择。详情可参考SerializedCache的源码。
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      // blocking：默认为false，当指定为true时将采用BlockingCache进行封装，blocking，阻塞的意思，使用BlockingCache会在查询缓
      // 存时锁住对应的Key，如果缓存命中了则会释放对应的锁，否则会在查询数据库以后再释放锁，这样可以阻止并发情况下多个线程同时
      // 查询数据。 简单理解，也就是设置true时，在进行增删改之后的并发查询，只会有一条去数据库
      // 查询，而不会并发
      boolean blocking = context.getBooleanAttribute("blocking", false);
      Properties props = context.getChildrenAsProperties();
      // 根绝配置参数，使用新的缓存
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  private void parameterMapElement(List<XNode> list) {
    for (XNode parameterMapNode : list) {
      String id = parameterMapNode.getStringAttribute("id");
      String type = parameterMapNode.getStringAttribute("type");
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  private void resultMapElements(List<XNode> list) {
    // 遍历处理每个resultMap
    for (XNode resultMapNode : list) {
      try {
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  private ResultMap resultMapElement(XNode resultMapNode) {
    return resultMapElement(resultMapNode, Collections.emptyList(), null);
  }

  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    //   <resultMap id="authorResultMap" type="Author">
    //    <id column="author_id" property="id"/>
    //    <result column="author_name" property="name"/>
    //    <collection property="posts" ofType="Post">
    //      <id property="id" column="post_id"/>
    //      <result column="post_id" property="id"/>
    //      <result column="post_content" property="content"/>
    //    </collection>
    //  </resultMap>

    // <resultMap id="resultMapAccount" type="org.apache.ibatis.submitted.nestedresulthandler_association.Account">
    //        <id property="accountUuid" column="account_uuid" />
    //        <result property="accountName" column="account_name" />
    //        <result property="birthDate" column="birth_date" />
    //        <association property="address" javaType="org.apache.ibatis.submitted.nestedresulthandler_association.AccountAddress">
    //            <id property="accountUuid" column="account_uuid" />
    //            <result property="zipCode" column="zip_code" />
    //            <result property="address" column="address" />
    //        </association>
    //    </resultMap>

    //   <select id="getUserUsingXml" resultType="org.apache.ibatis.submitted.optional_on_mapper_method.User">
    //    select * from users where id = #{id}
    //  </select>
    // 获取结果类类型
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));
    // 根据别名获取结果类
    Class<?> typeClass = resolveClass(type);
    if (typeClass == null) {
      // 如果未查询到结果类
      typeClass = inheritEnclosingType(resultMapNode, enclosingType);
    }
    // 鉴定器
    Discriminator discriminator = null;
    List<ResultMapping> resultMappings = new ArrayList<>(additionalResultMappings);
    List<XNode> resultChildren = resultMapNode.getChildren();
    for (XNode resultChild : resultChildren) {
      //     <resultMap id="blogUsingConstructorWithResultMapAndProperties" type="Blog">
      //        <constructor>
      //            <idArg column="id" javaType="_int"/>
      //            <arg column="title" javaType="java.lang.String"/>
      //            <arg javaType="org.apache.ibatis.domain.blog.Author" resultMap="org.apache.ibatis.binding.BoundAuthorMapper.authorResultMapWithProperties"/>
      //            <arg column="id" javaType="java.util.List" select="selectPostsForBlog"/>
      //        </constructor>
      //    </resultMap>
      if ("constructor".equals(resultChild.getName())) {
        //   <resultMap id="personMapLoop" type="Person">
        //        <id property="id" column="id"/>
        //        <result property="firstName" column="firstName"/>
        //        <result property="lastName" column="lastName"/>
        //        <discriminator column="personType" javaType="String">
        //            <case value="EmployeeType" resultMap="employeeMapLoop"/>
        //        </discriminator>
        //    </resultMap>
        // 如果是构造节点
        // 配置构造过程，传入参数节点，参类类型，相应结果映射resultMappings
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {
        // 如果是鉴定器节点
        // 配置鉴定器过程，传入参数节点，参类类型，相应结果映射discriminator
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
        // 都不是上述节点,从上下文构建结果映射，传入参数节点，参类类型，相应结果映射resultMappings
        List<ResultFlag> flags = new ArrayList<>();
        // 节点包含id，设置标志位为ID
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);
        }
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    // 获取标签resultMap 的属性id
    String id = resultMapNode.getStringAttribute("id",
            resultMapNode.getValueBasedIdentifier());
    //   <resultMap id="employeeMap" type="Employee" extends="personMap">
    //        <result property="jobTitle" column="jobTitle"/>
    //        <discriminator column="employeeType" javaType="String">
    //            <case value="DirectorType" resultMap="directorMap"/>
    //        </discriminator>
    //    </resultMap>
    // 继承
    String extend = resultMapNode.getStringAttribute("extends");
    //   <resultMap autoMapping="true"
    //    type="org.apache.ibatis.submitted.constructor_automapping.Author"
    //    id="authorRM">
    //  </resultMap>
    // 是否开启自动映射
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
    // 用参数创建ResultMapResolver对象
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      // 获取ResultMap
      return resultMapResolver.resolve();
    } catch (IncompleteElementException e) {
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  // 继承封装类型
  protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
    //   <resultMap id="ChildsBinomesResultMap" type="org.apache.ibatis.submitted.nestedresulthandler_multiple_association.Binome">
    //        <association property="one" select="selectChildBeanById" column="idchild_from" javaType="org.apache.ibatis.submitted.nestedresulthandler_multiple_association.ChildBean" />
    //        <association property="two" select="selectChildBeanById" column="idchild_to"  javaType="org.apache.ibatis.submitted.nestedresulthandler_multiple_association.ChildBean" />
    //    </resultMap>
    // 如果标签为association且节点属性resultMap为空
    if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      //    <resultMap id="ChildsBinomesResultMap" type="org.apache.ibatis.submitted.nestedresulthandler_multiple_association.Binome">
      //        <association property="one" select="selectChildBeanById" column="idchild_from" javaType="org.apache.ibatis.submitted.nestedresulthandler_multiple_association.ChildBean" />
      //        <association property="two" select="selectChildBeanById" column="idchild_to"  javaType="org.apache.ibatis.submitted.nestedresulthandler_multiple_association.ChildBean" />
      //    </resultMap>
      String property = resultMapNode.getStringAttribute("property");
      // 如果属性不为空且封装类型不为空
      if (property != null && enclosingType != null) {
        // 创建元结果类
        // todo 此处有时间再详细看
        MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
        // 根据属性值，获取元结果类型
        return metaResultType.getSetterType(property);
      }
    } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      //     <resultMap id="personMapLoop" type="Person">
      //        <id property="id" column="id"/>
      //        <result property="firstName" column="firstName"/>
      //        <result property="lastName" column="lastName"/>
      //        <discriminator column="personType" javaType="String">
      //            <case value="EmployeeType" resultMap="employeeMapLoop"/>
      //        </discriminator>
      //    </resultMap>
      return enclosingType;
    }
    return null;
  }

  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) {
    List<XNode> argChildren = resultChild.getChildren();
    // 处理构造节点，设置结果标记
    for (XNode argChild : argChildren) {
      List<ResultFlag> flags = new ArrayList<>();
      flags.add(ResultFlag.CONSTRUCTOR);
      if ("idArg".equals(argChild.getName())) {
        flags.add(ResultFlag.ID);
      }
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<>();
    for (XNode caseChild : context.getChildren()) {
      String value = caseChild.getStringAttribute("value");
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
      discriminatorMap.put(value, resultMap);
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  private void sqlElement(List<XNode> list) {
    // 是上下文中的数据库编号是否为空
    if (configuration.getDatabaseId() != null) {
      sqlElement(list, configuration.getDatabaseId());
    }
    sqlElement(list, null);
  }

  private void sqlElement(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      //    <select id="select1" databaseId="hsql"
      //        resultType="string" parameterType="int">
      //        select name from hsql where
      //        id=#{value}
      //    </select>
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");
      id = builderAssistant.applyCurrentNamespace(id, false);
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        sqlFragments.put(id, context);
      }
    }
  }

  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      return requiredDatabaseId.equals(databaseId);
    }
    if (databaseId != null) {
      return false;
    }
    if (!this.sqlFragments.containsKey(id)) {
      return true;
    }
    // skip this fragment if there is a previous one with a not null databaseId
    XNode context = this.sqlFragments.get(id);
    return context.getStringAttribute("databaseId") == null;
  }

  //从上下文构建结果映射
  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) {
    String property;
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      property = context.getStringAttribute("property");
    }
    // 获取节点属性值
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String nestedSelect = context.getStringAttribute("select");
    String nestedResultMap = context.getStringAttribute("resultMap", () ->
        processNestedResultMappings(context, Collections.emptyList(), resultType));
    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");
    String typeHandler = context.getStringAttribute("typeHandler");
    String resultSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");
    // 判断是否进行懒加载
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    // 根据别名获取javaType结果类
    Class<?> javaTypeClass = resolveClass(javaType);
    // 根据别名获取typeHandler结果类
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    // 根据jdbcType名称获取jdbcType
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    // 根据配置，映射建造辅助对象，创建结果映射对象
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }

  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) {
    if (Arrays.asList("association", "collection", "case").contains(context.getName())
        && context.getStringAttribute("select") == null) {
      validateCollection(context, enclosingType);
      ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
      return resultMap.getId();
    }
    return null;
  }

  protected void validateCollection(XNode context, Class<?> enclosingType) {
    if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
        && context.getStringAttribute("javaType") == null) {
      MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
      String property = context.getStringAttribute("property");
      if (!metaResultType.hasSetter(property)) {
        throw new BuilderException(
            "Ambiguous collection type for property '" + property + "'. You must specify 'javaType' or 'resultMap'.");
      }
    }
  }

  private void bindMapperForNamespace() {
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        // ignore, bound type is not required
      }
      if (boundType != null && !configuration.hasMapper(boundType)) {
        // Spring may not know the real resource name so we set a flag
        // to prevent loading again this resource from the mapper interface
        // look at MapperAnnotationBuilder#loadXmlResource
        configuration.addLoadedResource("namespace:" + namespace);
        configuration.addMapper(boundType);
      }
    }
  }

}
