package com.fleury.metrics.agent.transformer.visitors;

import static com.fleury.metrics.agent.config.Configuration.staticFinalFieldName;
import static com.fleury.metrics.agent.model.LabelUtil.getLabelNames;
import static com.fleury.metrics.agent.transformer.util.CollectionUtil.isNotEmpty;

import com.fleury.metrics.agent.model.Metric;
import com.fleury.metrics.agent.reporter.PrometheusMetricSystem;
import com.fleury.metrics.agent.transformer.util.OpCodeUtil;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.util.List;


public class StaticInitializerMethodVisitor extends AdviceAdapter {

    private final List<Metric> classMetrics;
    private final String className;

    public StaticInitializerMethodVisitor(MethodVisitor mv, List<Metric> classMetrics, String className, int access, String name, String desc) {
        super(ASM5, mv, access, name, desc);

        this.className = className;
        this.classMetrics = classMetrics;
    }

    @Override
    public void visitCode() {
        super.visitCode();

        for (Metric metric : classMetrics) {
            addMetric(metric);
        }
    }

    private void addMetric(Metric metric) {
        // load name
        mv.visitLdcInsn(metric.getName());

        // load labels
        if (isNotEmpty(metric.getLabels())) {
            if (metric.getLabels().size() > 5) {
                throw new IllegalStateException("Maximum labels per metric is 5. "
                        + metric.getName() + " has " + metric.getLabels().size());
            }

            mv.visitInsn(OpCodeUtil.getIConstOpcodeForInteger(metric.getLabels().size()));
            mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(String.class));

            List<String> labelNames = getLabelNames(metric.getLabels());
            for (int i = 0; i < labelNames.size(); i++) {
                mv.visitInsn(DUP);
                mv.visitInsn(OpCodeUtil.getIConstOpcodeForInteger(i));
                mv.visitLdcInsn(labelNames.get(i));
                mv.visitInsn(AASTORE);
            }
        }
        // or null if non labels
        else {
            mv.visitInsn(ACONST_NULL);
        }

        // load doc
        mv.visitLdcInsn(metric.getDoc() == null ? "empty doc" : metric.getDoc());

        // call PrometheusMetricSystem.createAndRegisterCounted/Timed/Gauged(...)
        mv.visitMethodInsn(INVOKESTATIC, Type.getInternalName(PrometheusMetricSystem.class),
                "createAndRegister" + metric.getType().name(),
                Type.getMethodDescriptor(
                        Type.getType(metric.getType().getCoreType()),
                        Type.getType(String.class), Type.getType(String[].class), Type.getType(String.class)),
                false);

        // store metric in class static field
        mv.visitFieldInsn(PUTSTATIC, className, staticFinalFieldName(metric),
                Type.getDescriptor(metric.getType().getCoreType()));
    }
}
