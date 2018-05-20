package com.fleury.metrics.agent.transformer.visitors.injectors;

import static com.fleury.metrics.agent.model.LabelUtil.getLabelVarIndex;
import static com.fleury.metrics.agent.model.LabelUtil.getNestedLabelVar;
import static com.fleury.metrics.agent.model.LabelUtil.isLabelVarNested;
import static com.fleury.metrics.agent.model.LabelUtil.isTemplatedLabelValue;
import static com.fleury.metrics.agent.model.LabelUtil.isThis;
import static com.fleury.metrics.agent.transformer.util.CollectionUtil.isNotEmpty;

import com.fleury.metrics.agent.introspector.GenericClassIntrospector;
import com.fleury.metrics.agent.model.LabelUtil;
import com.fleury.metrics.agent.model.Metric;
import com.fleury.metrics.agent.reporter.PrometheusMetricSystem;
import com.fleury.metrics.agent.transformer.util.OpCodeUtil;
import com.fleury.metrics.agent.transformer.visitors.MetricAdapter;

import org.apache.commons.beanutils.PropertyUtils;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.util.List;

/**
 *
 * @author Will Fleury
 */
public abstract class AbstractInjector implements Injector, Opcodes {

    public static final String METRIC_REPORTER_CLASSNAME = Type.getInternalName(PrometheusMetricSystem.class);

    static {
        PropertyUtils.addBeanIntrospector(new GenericClassIntrospector());
    }

    protected final AdviceAdapter aa;
    protected final MethodVisitor mv;
    protected final Type[] argTypes;
    protected final int access;
    protected final String className;

    public AbstractInjector(AdviceAdapter aa, String className, Type[] argTypes, int access) {
        this.aa = aa;
        this.className = className;
        this.argTypes = argTypes;
        this.access = access;
        this.mv = ((MetricAdapter)aa).getMethodVistor();
    }

    @Override
    public void injectAtMethodEnter() {
    }

    @Override
    public void injectAtVisitMaxs(int maxStack, int maxLocals) {
    }

    @Override
    public void injectAtMethodExit(int opcode) {
    }

    protected void injectLabelsToStack(Metric metric) {
        List<String> labelValues = LabelUtil.getLabelValues(metric.getLabels());

        if (isNotEmpty(labelValues)) {
            mv.visitInsn(OpCodeUtil.getIConstOpcodeForInteger(labelValues.size()));
            mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(String.class));

            for (int i = 0; i < labelValues.size(); i++) {
                mv.visitInsn(DUP);
                mv.visitInsn(OpCodeUtil.getIConstOpcodeForInteger(i));
                injectLabelValueToStack(labelValues.get(i));
            }

        } else {
            mv.visitInsn(ACONST_NULL);
        }
    }

    private void injectLabelValueToStack(String labelValue) {
        if (!isTemplatedLabelValue(labelValue)) {
            mv.visitLdcInsn(labelValue);
        } 
        else {
            if (isThis(labelValue)) {
                mv.visitVarInsn(ALOAD, 0); //aa.loadThis();
            }
            
            else {
                int argIndex = getLabelVarIndex(labelValue);

                boxParameterAndLoad(argIndex);
            }

            if (isLabelVarNested(labelValue)) {
                mv.visitLdcInsn(getNestedLabelVar(labelValue));

                mv.visitMethodInsn(INVOKESTATIC, Type.getInternalName(PropertyUtils.class),
                        "getNestedProperty",
                        Type.getMethodDescriptor(
                                Type.getType(Object.class),
                                Type.getType(Object.class), Type.getType(String.class)),
                        false);
            }

            mv.visitMethodInsn(INVOKESTATIC, Type.getInternalName(String.class),
                    "valueOf",
                    Type.getMethodDescriptor(
                            Type.getType(String.class),
                            Type.getType(Object.class)),
                    false);
        }

        mv.visitInsn(AASTORE);
    }

    private void boxParameterAndLoad(int argIndex) {
        Type type = argTypes[argIndex];
        int stackIndex = getStackIndex(argIndex);
        
        switch (type.getSort()) {
            case Type.OBJECT: //no need to box Object
                mv.visitVarInsn(ALOAD, stackIndex);
                break;
                
            default:
                // aa.loadArg(argIndex); //doesn't work...
                mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), stackIndex);
                aa.valueOf(type);
                break;
        }
    }
    
    private int getStackIndex(int arg) {
        int index = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < arg; i++) {
            index += argTypes[i].getSize();
        }
        return index;
    }
}
