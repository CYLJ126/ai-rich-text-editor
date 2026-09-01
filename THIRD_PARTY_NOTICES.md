# Third-Party Notices

ARTE is distributed under the MIT License only for material that its copyright
holders are entitled to license. Third-party material remains subject to its own
terms. This file records the backend and deployment components identified during
the pre-release source review on 2026-08-30.

This is a human-maintained summary of known incorporated source and major direct
runtime dependencies. It is not a complete list of transitive dependencies. Release
artifacts must retain the license and notice files shipped inside dependency JARs,
and each release should generate a version-resolved SBOM and license report.

## Major direct backend dependencies

| Components / Maven coordinates | License noted by upstream | Upstream |
| --- | --- | --- |
| Spring Boot and Spring AI (`org.springframework.*`) | Apache-2.0 | [Spring Boot](https://github.com/spring-projects/spring-boot), [Spring AI](https://github.com/spring-projects/spring-ai) |
| MyBatis-Plus (`com.baomidou:*`) | Apache-2.0 | [baomidou/mybatis-plus](https://github.com/baomidou/mybatis-plus) |
| Druid (`com.alibaba:druid-spring-boot-4-starter`) | Apache-2.0 | [alibaba/druid](https://github.com/alibaba/druid) |
| Redisson (`org.redisson:*`) | Apache-2.0 | [redisson/redisson](https://github.com/redisson/redisson) |
| Elasticsearch Java / REST clients (`org.elasticsearch.client:*`) | Apache-2.0 | [elastic/elasticsearch-java](https://github.com/elastic/elasticsearch-java) |
| Jackson (`tools.jackson:*`, `com.fasterxml.jackson:*`) | Apache-2.0 | [FasterXML/jackson](https://github.com/FasterXML/jackson) |
| Fastjson2 (`com.alibaba.fastjson2:fastjson2`) | Apache-2.0 | [alibaba/fastjson2](https://github.com/alibaba/fastjson2) |
| Apache Log4j, FreeMarker, PDFBox, Commons Text | Apache-2.0 | [Apache Software Foundation](https://github.com/apache) |
| ZXing (`com.google.zxing:*`) and Gson (`com.google.code.gson:gson`) | Apache-2.0 | [zxing/zxing](https://github.com/zxing/zxing), [google/gson](https://github.com/google/gson) |
| EasyCaptcha (`com.github.whvcse:easy-captcha`) | Apache-2.0 | [ele-admin/EasyCaptcha](https://github.com/ele-admin/EasyCaptcha) |
| LangChain4j (`dev.langchain4j:*`) | Apache-2.0 | [langchain4j/langchain4j](https://github.com/langchain4j/langchain4j) |
| Hutool (`cn.hutool:hutool-all`) | MulanPSL-2.0 | [dromara/hutool](https://github.com/dromara/hutool) |
| Lombok (`org.projectlombok:lombok`) | MIT | [projectlombok/lombok](https://github.com/projectlombok/lombok) |
| jsoup (`org.jsoup:jsoup`) | MIT | [jhy/jsoup](https://github.com/jhy/jsoup) |
| Qiniu Java SDK (`com.qiniu:qiniu-java-sdk`) | MIT | [qiniu/java-sdk](https://github.com/qiniu/java-sdk) |
| Bouncy Castle (`org.bouncycastle:*`) | MIT-style Bouncy Castle License | [bcgit/bc-java](https://github.com/bcgit/bc-java) |
| ONNX Runtime, pulled by transformer embeddings | MIT | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) |
| Protocol Buffers (`com.google.protobuf:protobuf-java`) | BSD-3-Clause | [protocolbuffers/protobuf](https://github.com/protocolbuffers/protobuf) |
| AspectJ Weaver (`org.aspectj:aspectjweaver`) | Eclipse Public License | [eclipse-aspectj/aspectj](https://github.com/eclipse-aspectj/aspectj) |
| Jakarta XML Binding / Servlet APIs | EPL-2.0 or GPL-2.0 with Classpath Exception (artifact-specific) | [jakartaee/jaxb-api](https://github.com/jakartaee/jaxb-api), [jakartaee/servlet](https://github.com/jakartaee/servlet) |
| MySQL Connector/J (`com.mysql:mysql-connector-j`) | GPL-2.0 with Universal FOSS Exception 1.0 | [mysql/mysql-connector-j](https://github.com/mysql/mysql-connector-j) |

MySQL Connector/J is not MIT-licensed. Its Universal FOSS Exception is material
to distribution with an MIT-licensed application; redistributors should retain
the connector's bundled license information and verify the exact release terms.

### MySQL Connector/J 8.0.33

- Component: `com.mysql:mysql-connector-j:8.0.33`
- Copyright: Oracle and/or its affiliates
- License: GPL-2.0 with Universal FOSS Exception 1.0
- Usage: unmodified runtime JDBC driver used by ARTE to connect to MySQL
- Source: [mysql/mysql-connector-j](https://github.com/mysql/mysql-connector-j/tree/release/8.0)

The license text above was extracted from the `LICENSE` file bundled in the
8.0.33 driver JAR. ARTE's MIT License does not relicense Connector/J.

## Removed dependency

`com.belerweb:pinyin4j:2.5.1` was removed before the first public release because
it was unused and its published POM/license provenance was not clear enough to
justify retaining it. The Maven POM labels it BSD, while the repository describes
it as a copy of an older upstream project; the downloaded JAR does not include a
standalone license file.

## Models, services, and operator-provided content

Model weights, tokenizer files, third-party APIs, uploaded content, and externally
deployed services are not relicensed by ARTE. Operators are responsible for the
terms applicable to the particular models, data, and services they configure.
