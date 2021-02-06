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
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * 构建XMLConfig,建造者模式
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  // 是否已被解析
  private boolean parsed;
  // 解析器
  private final XPathParser parser;
  // 环境
  private String environment;
  // 本地反射工厂，采用默认反射工厂
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  // 以下三个方法，采用字符流构建XMLConfig
  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  // 以下三个，采用字节流攻坚XMLConfig
  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  /**
   * @param inputStream 配置文件xml字节流
   * @param environment 覆盖配置文件xml中的default中的内容到 {@link XMLConfigBuilder}，即采用什么环境
   * @param props       覆盖配置文件xml中的properties中的内容到 {@link XPathParser}和{@link Configuration}
   */
  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  // 此方法都会被上述方法使用
  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  public Configuration parse() {
    // 如果已经被解析过了，直接抛出异常，XMLConfigBuilder构建的配置文件，只能被加载一次
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    // 第一次加载将解析属性设置为已解析，因为单线程启动，所以直接设置为true，可判断是否重复解析
    parsed = true;
    // 获取标签configuration中元素，进行解析配置文件
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  private void parseConfiguration(XNode root) {
    try {
      // issue #117 read properties first
      // 获取properties标签数据
      propertiesElement(root.evalNode("properties"));
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      // 加载自定义配置vfs
      //  VFS含义是虚拟文件系统；主要是通过程序能够方便读取本地文件系统、FTP文件系统等系统中的文件资源。
      //  Mybatis中提供了VFS这个配置，主要是通过该配置可以加载自定义的虚拟文件系统应用程序。
      // <bean id="sqlSessionFactory" class="org.mybatis.spring.SqlSessionFactoryBean">
      //   <property name="vfs"  ref="vfs"/>
      // </bean>
      loadCustomVfs(settings);
      // 加载自定义日志
      loadCustomLogImpl(settings);
      // 别名元素处理
      typeAliasesElement(root.evalNode("typeAliases"));
      // 拦截器插件 责任链
      pluginElement(root.evalNode("plugins"));
      // 对象工程，MyBatis 每次创建结果对象的新实例时，它都会使用一个对象工厂（ObjectFactory）实例来完成。 默认的对象工厂
      // 需要做的仅仅是实例化目标类，要么通过默认构造方法，要么在参数映射存在的时候通过参数构造方法来实例化。 如果
      // 想覆盖对象工厂的默认行为，则可以通过创建自己的对象工厂来实现。
      objectFactoryElement(root.evalNode("objectFactory"));
      // 对象包装工厂
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      // 反射工厂
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      // 其他方面设置
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      // 环境参数
      environmentsElement(root.evalNode("environments"));
      // MyBatis 可以根据不同的数据库厂商执行不同的语句，这种多厂商的支持是基于映射语句中的 databaseId 属性。
      // MyBatis 会加载不带 databaseId 属性和带有匹配当前数据库 databaseId 属性的所有语句。 如果同时找到带
      // 有 databaseId 和不带 databaseId 的相同语句，则后者会被舍弃。
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      // 自定义类型处理器
      typeHandlerElement(root.evalNode("typeHandlers"));
      // 接口sql映射器
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          // 设置自定义vfs到上下文
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    // <configuration>
    //  <settings>
    //    ...
    //    <setting name="logImpl" value="LOG4J"/>
    //    ...
    //  </settings>
    //</configuration>
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    // 设置日志实现到configuration 上下文
    configuration.setLogImpl(logImpl);
  }

  // //<typeAliases>
  ////  <typeAlias alias="Author" type="domain.blog.Author"/>
  ////  <typeAlias alias="Blog" type="domain.blog.Blog"/>
  ////  <typeAlias alias="Comment" type="domain.blog.Comment"/>
  ////  <typeAlias alias="Post" type="domain.blog.Post"/>
  ////  <typeAlias alias="Section" type="domain.blog.Section"/>
  ////  <typeAlias alias="Tag" type="domain.blog.Tag"/>
  ////</typeAliases>
  ////or
  ////<typeAliases>
  ////  <package name="domain.blog"/>
  ////</typeAliases>
  private void typeAliasesElement(XNode parent) {
    // 别名元素不为空
    if (parent != null) {
      // 遍历节点处理
      for (XNode child : parent.getChildren()) {
        // 节点元素之，是否是包
        if ("package".equals(child.getName())) {
          // 获取包名
          String typeAliasPackage = child.getStringAttribute("name");
          // 将包下的所有的pojo类注册到configuration 的typeAliasRegistry 上,@Alias 注解此时生效。
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          // 获取指定类别名
          String alias = child.getStringAttribute("alias");
          // 获取指定类全名
          String type = child.getStringAttribute("type");
          try {
            Class<?> clazz = Resources.classForName(type);
            // 注册类别名
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  //  MyBatis 允许你在某一点拦截已映射语句执行的调用。默认情况下,MyBatis 允许使用插件来拦截方法调用
  //  <plugins>
  //    <plugin interceptor="org.mybatis.example.ExamplePlugin">
  //      <property name="someProperty" value="100"/>
  //    </plugin>
  //  </plugins>
  // TODO: 2021-02-02 责任链模式
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        Properties properties = child.getChildrenAsProperties();
        // 读取配置，实例化插件
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();
        // 设置插件中的属性值
        interceptorInstance.setProperties(properties);
        // 添加到configuration 上下文的interceptorChain 中
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  // 对象工厂,可以自定义对象创建的方式,比如用对象池？
  // <objectFactory type="org.mybatis.example.ExampleObjectFactory">
  //   <property name="someProperty" value="100"/>
  // </objectFactory>
  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获取类全名
      String type = context.getStringAttribute("type");
      // 获取所有属性值
      Properties properties = context.getChildrenAsProperties();
      // 实例化对象工厂
      ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 对象工厂设置属性
      factory.setProperties(properties);
      // 添加到configuration objectFactory 中
      configuration.setObjectFactory(factory);
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获取类全名
      String type = context.getStringAttribute("type");
      // 实例化对象包装工厂
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 添加到configuration objectWrapperFactory 中
      configuration.setObjectWrapperFactory(factory);
    }
  }

  // <reflectorFactory type="com.yczuoxin.reflectorFactory.MyReflectorFactory"/>
  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获取类全名
      String type = context.getStringAttribute("type");
      // 实例化反射工厂
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 添加到configuration reflectorFactory 中
      configuration.setReflectorFactory(factory);
    }
  }

  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      Properties defaults = context.getChildrenAsProperties();
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) {
    // 如果配置了该值，则用该值，否则用默认值
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    configuration.setShrinkWhitespacesInSql(booleanValueOf(props.getProperty("shrinkWhitespacesInSql"), false));
    configuration.setDefaultSqlProviderType(resolveClass(props.getProperty("defaultSqlProviderType")));
  }

  //  环境
  //	<environments default="development">
  //	  <environment id="development">
  //	    <transactionManager type="JDBC">
  //	      <property name="..." value="..."/>
  //	    </transactionManager>
  //	    <dataSource type="POOLED">
  //	      <property name="driver" value="${driver}"/>
  //	      <property name="url" value="${url}"/>
  //	      <property name="username" value="${username}"/>
  //	      <property name="password" value="${password}"/>
  //	    </dataSource>
  //	  </environment>
  //	</environments>
  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      // 如果 XMLConfigBuilder 方法没有带environment，则采用配置文件中的
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      for (XNode child : context.getChildren()) {
        String id = child.getStringAttribute("id");
        // 当id配置的环境和default中对应的环境相同时，设置事务管理器
        if (isSpecifiedEnvironment(id)) {
          // 根据配置创建事务工厂
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 根据配置创建数据源工厂
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          // 获取数据源
          DataSource dataSource = dsFactory.getDataSource();
          // 建造者模式创建环境
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          // 添加到configuration environment 中
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  //<databaseIdProvider type="DB_VENDOR">
  //  <property name="MySQL" value="mysql"/>
  //  <property name="Oracle" value="oracle" />
  //</databaseIdProvider>
  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      // 糟糕的补丁以保持向后兼容性
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties();
      // 实例化数据库Id提供者对象
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
      // 数据库Id提供者设置属性
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    // 只有上下文configuration中的environment 不为空，且配置文件中databaseIdProvider不为空
    // 才会根据上下文中的environment，获取databaseId。并设置到上下文中
    if (environment != null && databaseIdProvider != null) {
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  //事务管理器
  //  <transactionManager type="JDBC">
  //   <property name="..." value="..."/>
  //  </transactionManager>
  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  //数据源
  //<dataSource type="POOLED">
  //  <property name="driver" value="${driver}"/>
  //  <property name="url" value="${url}"/>
  //  <property name="username" value="${username}"/>
  //  <property name="password" value="${password}"/>
  //</dataSource>
  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  //   类型处理器
//   <typeHandlers>
//        <typeHandler
//            handler="org.apache.ibatis.submitted.typehandler.Product$ProductIdTypeHandler"
//            javaType="org.apache.ibatis.submitted.typehandler.Product$ProductId"
//            jdbcType="INTEGER" />
//    </typeHandlers>
  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 如果配置处理器的标签属性为package
        if ("package".equals(child.getName())) {
          // 获取包名
          String typeHandlerPackage = child.getStringAttribute("name");
          // 将包下的类注册到 typeHandlerRegistry 属性中
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          // 获取java 中的类型
          String javaTypeName = child.getStringAttribute("javaType");
          // 获取jdbc中的类型
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          // 获取类型处理器
          String handlerTypeName = child.getStringAttribute("handler");
          // 获取java类型对应的类
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          // 获取对应的jdbcType
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          // 获取类型处理器类
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          // 如果java类型类为空，直接注册
          // 否则 如果jdbcType 为空
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

//    <mappers>
//        <mapper resource="org/apache/ibatis/submitted/multipleresultsetswithassociation/Mapper.xml" />
//    </mappers>

  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 如果标签属性为包,扫描包下的mapper，添加到上下文configuration
        //  <mappers>
        //    <mapper resource="org/apache/ibatis/builder/xsd/BlogMapper.xml"/>
        //    <mapper url="file:./src/test/java/org/apache/ibatis/builder/xsd/NestedBlogMapper.xml"/>
        //    <mapper class="org.apache.ibatis.builder.xsd.CachedAuthorMapper"/>
        //    <package name="org.apache.ibatis.builder.mapper"/>
        //  </mappers>
        if ("package".equals(child.getName())) {
          String mapperPackage = child.getStringAttribute("name");
          configuration.addMappers(mapperPackage);
        } else {
          // 不为空，则获取标签中的resource，url, class
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          if (resource != null && url == null && mapperClass == null) {
            // resource不为为空
            ErrorContext.instance().resource(resource);
            // 加载配置
            try(InputStream inputStream = Resources.getResourceAsStream(resource)) {
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
              // 解析配置文件
              mapperParser.parse();
            }
          } else if (resource == null && url != null && mapperClass == null) {
            // url不为为空
            ErrorContext.instance().resource(url);
            try(InputStream inputStream = Resources.getUrlAsStream(url)){
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
              mapperParser.parse();
            }
          } else if (resource == null && url == null && mapperClass != null) {
            // mapperClass不为为空
            // 创建mapperClass类
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            // 映射接口添加到configuration上下文中
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    }
    if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    }
    return environment.equals(id);
  }

}
