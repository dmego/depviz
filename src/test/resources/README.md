# 测试资源

- [tree/demo.txt](tree/demo.txt)（451 occurrence、208 GA、1 模块）与 [projects/demo/pom.xml](projects/demo/pom.xml)：保留真实 Maven verbose 树的规模回归，以及实际 Maven、HTTP、浏览器 E2E 扫描入口。
- [tree/multi.txt](tree/multi.txt)（19 occurrence、13 GA、4 模块、15 生效、3 conflict、1 duplicate、最大深度 3）：手写语义样本。覆盖两个模块分别选择 slf4j 1.7.36/2.0.13、同模块省略、等深度先声明获胜、classifier、vm/sm/snu 与自由文本注解。根顺序 lib/parent/util/app，特意区别于磁盘发现的 parent/app/util/lib。
- [golden/multi.json](golden/multi.json)：人工逐行审核的完整预期，严格比较 project、GA/版本索引、occurrence 字段、根与模块元数据顺序。修改时同时审核小树及预期，不从被测序列化器自动生成。
- [projects/multi/pom.xml](projects/multi/pom.xml)、[app](projects/multi/app/pom.xml)、[app/util](projects/multi/app/util/pom.xml)、[lib](projects/multi/lib/pom.xml)：四个自包含 POM，供模块发现和元数据对齐；手写 multi 树的依赖形态独立于这些 POM。
