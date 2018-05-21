package com.fleury.metrics.agent.transformer.visitors.injectors;

import static com.fleury.metrics.agent.config.Configuration.staticFinalFieldName;
import static com.fleury.metrics.agent.model.MetricType.Gauged;

import com.fleury.metrics.agent.model.Metric;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Only currently supports IN_FLIGHT mode which means it tracks the number of method calls in flight.
 * Transforms from
 *
 * <pre>
 * public void someMethod() {
 *     //original method code
 * }
 * </pre>
 *
 * To
 *
 * <pre>
 * public void someMethod() {
 *     PrometheusMetricSystem.recordGaugeInc(GAUGE, labels);
 *     try {
 *
 *         //original method code
 *
 *     } finally {
 *         PrometheusMetricSystem.recordGaugeDec(GAUGE, labels);
 *     }
 * }
 * </pre>
 *
 * @author Will Fleury
 */
public class GaugeInjector extends AbstractInjector {

    private static final String INC_METHOD = "recordGaugeInc";
    private static final String DEC_METHOD = "recordGaugeDec";
    private static final String SIGNATURE = Type.getMethodDescriptor(
            Type.VOID_TYPE,
            Type.getType(Gauged.getCoreType()), Type.getType(String[].class));

    private final Metric metric;

    private Label startFinally;

    public GaugeInjector(Metric metric, AdviceAdapter aa, String className, Type[] argTypes, int access) {
        super(aa, className, argTypes, access);
        this.metric = metric;
    }

    @Override
    public void injectAtMethodEnter() {
        startFinally = new Label();
        mv.visitLabel(startFinally);

        mv.visitFieldInsn(GETSTATIC, className, staticFinalFieldName(metric), Type.getDescriptor(Gauged.getCoreType()));
        injectLabelsToStack(metric);

        mv.visitMethodInsn(INVOKESTATIC, METRIC_REPORTER_CLASSNAME, INC_METHOD, SIGNATURE, false);
    }

    @Override
    public void injectAtVisitMaxs(int maxStack, int maxLocals) {
        Label endFinally = new Label();
        mv.visitTryCatchBlock(startFinally, endFinally, endFinally, null);
        mv.visitLabel(endFinally);

        onFinally(ATHROW);
        mv.visitInsn(ATHROW);
    }

    @Override
    public void injectAtMethodExit(int opcode) {
        if (opcode != ATHROW) {
            onFinally(opcode);
        }
    }

    private void onFinally(int opcode) {
        mv.visitFieldInsn(GETSTATIC, className, staticFinalFieldName(metric), Type.getDescriptor(Gauged.getCoreType()));
        injectLabelsToStack(metric);

        mv.visitMethodInsn(INVOKESTATIC, METRIC_REPORTER_CLASSNAME, DEC_METHOD, SIGNATURE, false);
    }
}
