package com.fleury.metrics.agent.transformer.visitors.injectors;

import static com.fleury.metrics.agent.config.Configuration.staticFinalFieldName;
import static com.fleury.metrics.agent.model.MetricType.Timed;

import com.fleury.metrics.agent.model.Metric;

import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

/**
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
 *     long startTimer = System.nanoTime();
 *     try {
 *
 *         //original method code
 *
 *     } finally {
 *         PrometheusMetricSystem.recordTime(TIMER, labels);
 *     }
 * }
 * </pre>
 *
 * @author Will Fleury
 */
public class TimerInjector extends AbstractInjector {

    private static final String METHOD = "recordTime";
    private static final String SIGNATURE = Type.getMethodDescriptor(
            Type.VOID_TYPE,
            Type.getType(Timed.getCoreType()), Type.getType(String[].class), Type.LONG_TYPE);

    private final Metric metric;
    
    private int startTimeVar;
    private Label startFinally;

    public TimerInjector(Metric metric, AdviceAdapter aa, String className, Type[] argTypes, int access) {
        super(aa, className, argTypes, access);
        this.metric = metric;
    }

    @Override
    public void injectAtMethodEnter() {
        startFinally = new Label();
        startTimeVar = aa.newLocal(Type.LONG_TYPE);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        mv.visitVarInsn(LSTORE, startTimeVar);
        mv.visitLabel(startFinally);
    }

    @Override
    public void injectAtVisitMaxs(int maxStack, int maxLocals) {
        Label endFinally = new Label();
        mv.visitTryCatchBlock(startFinally, endFinally, endFinally, null);
        mv.visitLabel(endFinally);
        
        onFinally(ATHROW);
        mv.visitInsn(ATHROW);
//        Label timeEnd = new Label();
//        mv.visitLabel(timeEnd);
    }

    @Override
    public void injectAtMethodExit(int opcode) {
        if (opcode != ATHROW) {
            onFinally(opcode);
        }
    }

    private void onFinally(int opcode) {
        if (opcode == ATHROW)
            mv.visitInsn(DUP);
        else
            mv.visitInsn(ACONST_NULL);

        mv.visitFieldInsn(GETSTATIC, className, staticFinalFieldName(metric), Type.getDescriptor(Timed.getCoreType()));
        injectLabelsToStack(metric);

        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        mv.visitVarInsn(LLOAD, startTimeVar);
        mv.visitInsn(LSUB);
        mv.visitMethodInsn(INVOKESTATIC, METRIC_REPORTER_CLASSNAME, METHOD, SIGNATURE, false);
    }
}
