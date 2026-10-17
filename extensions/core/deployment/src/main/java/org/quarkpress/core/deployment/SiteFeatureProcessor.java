package org.quarkpress.core.deployment;

import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.DotName;
import org.quarkpress.core.deployment.GateClassifier.Classification;
import org.quarkpress.core.deployment.GateClassifier.Gate;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.core.runtime.GateKind;

class SiteFeatureProcessor {

    @BuildStep
    void gates(
            BeanArchiveIndexBuildItem beanArchive,
            List<SiteFeatureBuildItem> features,
            BuildProducer<AnnotationsTransformerBuildItem> transformers) {
        Classification classification = GateClassifier.classify(beanArchive.getIndex());
        Set<String> declared = new TreeSet<>();
        for (SiteFeatureBuildItem feature : features) {
            declared.add(feature.key());
        }
        List<String> problems = new ArrayList<>(classification.problems());
        problems.addAll(GateClassifier.problems(classification.gates(), declared));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("QuarkPress @SiteFeature problems:\n- " + String.join("\n- ", problems));
        }
        Map<String, Gate> byMethod = new HashMap<>();
        Map<DotName, List<Gate>> byClass = new LinkedHashMap<>();
        for (Gate gate : classification.gates()) {
            if (gate.byClass()) {
                byClass.computeIfAbsent(gate.coveredBy().name(), name -> new ArrayList<>())
                        .add(gate);
            } else {
                byMethod.put(GateClassifier.methodKey(gate.method()), gate);
            }
        }
        transformers.produce(new AnnotationsTransformerBuildItem(AnnotationTransformation.forMethods()
                .when(ctx -> byMethod.containsKey(
                        GateClassifier.methodKey(ctx.declaration().asMethod())))
                .transform(ctx -> {
                    Gate gate = byMethod.get(
                            GateClassifier.methodKey(ctx.declaration().asMethod()));
                    ctx.add(AnnotationInstance.builder(GateClassifier.GATE)
                            .add("key", gate.key())
                            .add("kind", gate.kind())
                            .build());
                })));
        transformers.produce(new AnnotationsTransformerBuildItem(AnnotationTransformation.forClasses()
                .when(ctx -> byClass.containsKey(ctx.declaration().asClass().name()))
                .transform(ctx -> {
                    List<Gate> gates = byClass.get(ctx.declaration().asClass().name());
                    String[] methods = new String[gates.size()];
                    GateKind[] kinds = new GateKind[gates.size()];
                    for (int i = 0; i < gates.size(); i++) {
                        methods[i] = GateClassifier.methodId(gates.get(i).method());
                        kinds[i] = gates.get(i).kind();
                    }
                    ctx.add(AnnotationInstance.builder(GateClassifier.GATE)
                            .add("key", gates.get(0).key())
                            .add("byClass", true)
                            .add("methods", methods)
                            .add("kinds", kinds)
                            .build());
                })));
    }
}
