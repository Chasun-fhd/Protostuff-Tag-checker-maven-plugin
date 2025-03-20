package org.chasun.maven.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.ACC_STATIC;


@Mojo(name = "check-protostuff-tag", defaultPhase = LifecyclePhase.COMPILE)
public class ProtoStuffCheckerMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File classesDirectory;
    @Parameter(property = "failOnError", defaultValue = "true")
    private boolean failOnError;

    private static Log log;

    @Override
    public void execute() throws MojoExecutionException {
        log = getLog();
        try {
            ClassAnalyzer analyzer = new ClassAnalyzer();
            Map<String, Map<Integer, FieldInfo>> violations = analyzer.analyze(classesDirectory);
            if (!violations.isEmpty()) {
                new ConsoleReporter(log).report(violations);
                if (failOnError) {
                    throw new MojoExecutionException("发现重复的@Tag值，构建失败");
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("插件执行失败", e);
        }
    }


    static class ClassAnalyzer {
        public Map<String, Map<Integer, FieldInfo>> analyze(File classesDir) throws Exception {
            Map<String, Map<Integer, FieldInfo>> result = new HashMap<>();

            Files.walk(classesDir.toPath())
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> {
                        try (InputStream is = Files.newInputStream(path)) {
                            ClassReader reader = new ClassReader(is);
                            ClassNode classNode = new ClassNode();
                            reader.accept(classNode, ClassReader.SKIP_DEBUG);
                            if (log.isDebugEnabled()) {
                                log.debug("Analyze class:" + path);
                            }
                            processClass(classNode, result);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });

            return result.entrySet().stream()
                    .filter(e -> !e.getValue().isEmpty())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        private void processClass(ClassNode classNode, Map<String, Map<Integer, FieldInfo>> result) {
            Map<Integer, FieldInfo> tagMap = new HashMap<>();

            classNode.fields.stream()
                    .filter(field -> !isStaticField(field))
                    .forEach(field -> {
                        Integer tagValue = extractTagValue(field);
                        if (log.isDebugEnabled()) {
                            log.debug("ProcessClass class:" + classNode.name + " processClass field:" + field + " tag:" + tagValue);
                        }
                        if (tagValue != null) {
                            FieldInfo info = new FieldInfo(
                                    Type.getObjectType(classNode.name).getClassName(),
                                    field.name,
                                    tagValue
                            );

                            if (tagMap.containsKey(tagValue)) {
                                result.computeIfAbsent(classNode.name, k -> new HashMap<>())
                                        .put(tagValue, info);
                            } else {
                                tagMap.put(tagValue, info);
                            }
                        }
                    });
        }

        private boolean isStaticField(FieldNode field) {
            return (field.access & ACC_STATIC) != 0;
        }

        private Integer extractTagValue(FieldNode field) {
            if (field.visibleAnnotations == null) return null;

            for (AnnotationNode ann : field.visibleAnnotations) {
                if ("Lio/protostuff/Tag;".equals(ann.desc)) {
                    if (ann.values != null) {
                        for (int i = 0; i < ann.values.size(); i += 2) {
                            String key = (String) ann.values.get(i);
                            if ("value".equals(key) || (i == 0 && ann.values.size() == 1)) {
                                return (Integer) ann.values.get(i + 1);
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

    static class ConsoleReporter {
        private final Log log;

        public ConsoleReporter(Log log) {
            this.log = log;
        }

        public void report(Map<String, Map<Integer, FieldInfo>> violations) {
            log.error("发现重复的@Tag配置：");
            violations.forEach((className, tags) -> {
                log.error("类: " + className.replace('/', '.'));
                tags.forEach((tag, fieldInfo) -> {
                    log.error(String.format("  Tag %d 冲突字段:", tag));
                    log.error("    ▸ " + fieldInfo.toString());
                });
                log.error("----------------------------------------");
            });
        }
    }


    // 字段信息容器类
    static class FieldInfo {
        private final String className;
        private final String fieldName;
        private final int tagValue;

        public FieldInfo(String className, String fieldName, int tagValue) {
            this.className = className;
            this.fieldName = fieldName;
            this.tagValue = tagValue;
        }

        // Getter 方法
        public String getClassName() {
            return className.replace('/', '.');
        }

        public String getFieldName() {
            return fieldName;
        }

        public int getTagValue() {
            return tagValue;
        }

        @Override
        public String toString() {
            return String.format("%s#%s (tag=%d)",
                    getClassName(),
                    fieldName,
                    tagValue
            );
        }
    }
}
