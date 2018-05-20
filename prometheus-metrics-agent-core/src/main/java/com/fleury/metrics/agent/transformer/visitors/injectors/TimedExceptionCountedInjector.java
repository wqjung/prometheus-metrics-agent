package com.fleury.metrics.agent.transformer.visitors.injectors;

import static com.fleury.metrics.agent.config.Configuration.staticFinalFieldName;
import static com.fleury.metrics.agent.model.MetricType.Counted;
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
 *     } catch (Throwable t) {
 *         PrometheusMetricSystem.recordCount(COUNTER, labels);
 *         throw t;
 *     } finally {
 *         PrometheusMetricSystem.recordTime(TIMER, labels);
 *     }
 * }
 * </pre>
 *
 * @author Will Fleury
 */
public class TimedExceptionCountedInjector extends AbstractInjector {
    
    private static final String EXCEPTION_COUNT_METHOD = "recordCount";
    private static final String EXCEPTION_COUNT_SIGNATURE = Type.getMethodDescriptor(
            Type.VOID_TYPE,
            Type.getType(Counted.getCoreType()), Type.getType(String[].class));
    
    private static final String TIMER_METHOD = "recordTime";
    private static final String TIMER_SIGNATURE = Type.getMethodDescriptor(
            Type.VOID_TYPE,
            Type.getType(Timed.getCoreType()), Type.getType(String[].class), Type.LONG_TYPE);
    
    private final Metric timerMetric;
    private final Metric exceptionMetric;
    
    private int startTimeVar;
    private Label startFinally;
    
    public TimedExceptionCountedInjector(Metric timerMetric, Metric exceptionMetric, AdviceAdapter aa,
                                         String className, Type[] argTypes, int access) {
        super(aa, className, argTypes, access);
        this.timerMetric = timerMetric;
        this.exceptionMetric = exceptionMetric;
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

        mv.visitFieldInsn(GETSTATIC, className, staticFinalFieldName(exceptionMetric), Type.getDescriptor(Counted.getCoreType()));
        injectLabelsToStack(exceptionMetric);
        mv.visitMethodInsn(INVOKESTATIC, METRIC_REPORTER_CLASSNAME, EXCEPTION_COUNT_METHOD,
                EXCEPTION_COUNT_SIGNATURE, false);

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
        mv.visitFieldInsn(GETSTATIC, className, staticFinalFieldName(timerMetric), Type.getDescriptor(Timed.getCoreType()));
        injectLabelsToStack(timerMetric);

        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
        mv.visitVarInsn(LLOAD, startTimeVar);
        mv.visitInsn(LSUB);
        mv.visitMethodInsn(INVOKESTATIC, METRIC_REPORTER_CLASSNAME, TIMER_METHOD, TIMER_SIGNATURE, false);
    }
}
