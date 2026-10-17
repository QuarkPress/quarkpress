package org.quarkpress.core.deployment.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.jandex.Index;
import org.jboss.jandex.MethodInfo;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.ContentFilter;
import org.quarkpress.core.FilterSpi;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.deployment.GateClassifier;
import org.quarkpress.core.deployment.GateClassifier.Classification;
import org.quarkpress.core.deployment.GateClassifier.Gate;
import org.quarkpress.core.runtime.GateKind;

class GateClassifierTest {

    private static Map<String, Gate> gates() throws IOException {
        Index index = Index.of(
                SiteFeature.class,
                FilterSpi.class,
                ContentFilter.class,
                GateSamples.Ping.class,
                GateSamples.Observer.class,
                GateSamples.Filter.class,
                GateSamples.Base.class,
                GateSamples.Defaults.class,
                GateSamples.Service.class,
                GateSamples.ResourceBase.class,
                GateSamples.Resource.class,
                GateSamples.MixedResource.class);
        Map<String, Gate> byName = new HashMap<>();
        Classification classification = GateClassifier.classify(index);
        assertThat(classification.problems()).isEmpty();
        for (Gate gate : classification.gates()) {
            byName.put(
                    gate.method().declaringClass().simpleName() + "."
                            + gate.method().name(),
                    gate);
        }
        return byName;
    }

    @Test
    void classifiesObserversFiltersAndOtherMethods() throws IOException {
        Map<String, Gate> gates = gates();
        assertThat(gates.get("Observer.on").kind()).isEqualTo(GateKind.OBSERVER);
        assertThat(gates.get("Filter.apply").kind()).isEqualTo(GateKind.FILTER);
        assertThat(gates.get("Service.hello").kind()).isEqualTo(GateKind.OTHER);
        assertThat(gates.get("Service.hello").key()).isEqualTo("demo");
    }

    @Test
    void classifiesRestResourceMethods() throws IOException {
        Map<String, Gate> gates = gates();
        assertThat(gates.get("Resource.get").kind()).isEqualTo(GateKind.RESOURCE);
        assertThat(gates.get("MixedResource.gated").kind()).isEqualTo(GateKind.RESOURCE);
    }

    @Test
    void aClassLevelGateOnAResourceCoversInheritedResourceMethods() throws IOException {
        Gate inherited = gates().get("ResourceBase.inheritedEndpoint");
        assertThat(inherited.kind()).isEqualTo(GateKind.RESOURCE);
        assertThat(inherited.coveredBy().simpleName()).isEqualTo("Resource");
    }

    @Test
    void methodLevelKeysBeatClassLevelKeys() throws IOException {
        assertThat(gates().get("Service.other").key()).isEqualTo("other");
    }

    @Test
    void classLevelGatesSkipPrivateStaticAndUnannotatedNeighbours() throws IOException {
        Map<String, Gate> gates = gates();
        assertThat(gates).doesNotContainKeys("Service.hidden", "Service.utility", "Service.<init>", "Filter.unrelated");
    }

    @Test
    void classLevelGatesCoverInheritedPublicMethodsAndObserversButNotObjectMethods() throws IOException {
        Map<String, Gate> gates = gates();
        assertThat(gates.get("Base.inherited").kind()).isEqualTo(GateKind.OTHER);
        assertThat(gates.get("Base.inherited").coveredBy().simpleName()).isEqualTo("Service");
        assertThat(gates.get("Base.onBase").kind()).isEqualTo(GateKind.OBSERVER);
        assertThat(gates.get("Service.overridden").byClass()).isTrue();
        assertThat(gates.get("Defaults.fromDefault").coveredBy().simpleName()).isEqualTo("Service");
        assertThat(gates.get("Service.required").kind()).isEqualTo(GateKind.OTHER);
        assertThat(gates)
                .doesNotContainKeys(
                        "Base.overridden",
                        "Base.internal",
                        "Defaults.required",
                        "Service.equals",
                        "Service.hashCode",
                        "Service.toString");
    }

    @Test
    void aFilterMethodMustMatchTheFilterParameterTypesExactly() throws IOException {
        List<Gate> applies = GateClassifier.gates(Index.of(
                        SiteFeature.class, FilterSpi.class, ContentFilter.class, GateSamples.LooseFilter.class))
                .stream()
                .filter(gate -> gate.method().name().equals("apply"))
                .toList();
        assertThat(applies)
                .extracting(gate -> GateClassifier.methodId(gate.method()) + "=" + gate.kind())
                .containsExactlyInAnyOrder(
                        "apply(java.lang.String,org.quarkpress.core.RenderContext)=FILTER",
                        "apply(java.lang.Object,org.quarkpress.core.RenderContext)=OTHER");
    }

    @Test
    void methodIdsSpellParameterTypesLikeReflection() throws IOException {
        Index index = Index.of(GateSamples.Signatures.class);
        MethodInfo mixed = index.getClassByName(GateSamples.Signatures.class).firstMethod("mixed");
        assertThat(GateClassifier.methodId(mixed)).isEqualTo("mixed(java.lang.String,int,[Ljava.lang.String;,[[J)");
    }

    @Test
    void reportsUnknownKeys() throws IOException {
        List<String> problems = GateClassifier.problems(List.copyOf(gates().values()), Set.of("demo"));
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("\"other\"").contains("no installed module declares");
    }
}
