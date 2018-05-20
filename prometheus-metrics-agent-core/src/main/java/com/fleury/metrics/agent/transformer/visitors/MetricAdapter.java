package com.fleury.metrics.agent.transformer.visitors;

import static com.fleury.metrics.agent.model.LabelUtil.validateLabelValues;
import static com.fleury.metrics.agent.model.Metric.mapByType;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;

import com.fleury.metrics.agent.model.Metric;
import com.fleury.metrics.agent.model.MetricType;
import com.fleury.metrics.agent.transformer.visitors.injectors.Injector;
import com.fleury.metrics.agent.transformer.visitors.injectors.InjectorFactory;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 *
 * @author Will Fleury
 */
public class MetricAdapter extends AdviceAdapter {

    private static final Logger LOGGER = Logger.getLogger(AdviceAdapter.class.getName());

    private final Map<MetricType, Metric> metrics;
    private final Type[] argTypes;
    private final String className;
    private final String methodName;
    private final int access;
    
    private List<Injector> injectors;

    public MetricAdapter(MethodVisitor mv, String className, int access, String name, String desc, List<Metric> metadata) {
        super(ASM5, mv, access, name, desc);

        this.className = className;
        this.methodName = name;
        this.argTypes = Type.getArgumentTypes(desc);
        this.access = access;
        this.metrics = mapByType(metadata);
    }

    public MethodVisitor getMethodVistor(){
        return mv;
    }

    @Override
    protected void onMethodEnter() {
        // LOGGER.log(INFO, "onMethodEnter {0}", className);
        if (metrics.isEmpty()) {
            injectors = Collections.emptyList();
            return;
        }

        LOGGER.log(FINE, "Metrics found on : {0}.{1}", new Object[] {className, methodName});

        injectors = InjectorFactory.createInjectors(metrics, this, className, argTypes, access);
        validateLabels();
        // LOGGER.log(INFO, "Loaded {0} injectors", injectors.size());

        for (Injector injector : injectors) {
            injector.injectAtMethodEnter();
        }
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // LOGGER.log(INFO, "visitMaxs");
        for (Injector injector : injectors) {
            injector.injectAtVisitMaxs(maxStack, maxLocals);
        }

        mv.visitMaxs(maxStack, maxLocals);
    }

    @Override
    protected void onMethodExit(int opcode) {
        // LOGGER.log(INFO, "onMethodExit {0}", className);
        for (Injector injector : injectors) {
            injector.injectAtMethodExit(opcode);
        }
    }

    private void validateLabels() {
        for (Metric metric : metrics.values()) {
            validateLabelValues(methodName, metric.getLabels(), argTypes);
        }
    }
}
