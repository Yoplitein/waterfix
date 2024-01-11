package net.yoplitein.wurm.waterfix;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javassist.bytecode.BadBytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.Descriptor;
import javassist.bytecode.LocalVariableAttribute;
import javassist.bytecode.MethodInfo;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtPrimitiveType;
import javassist.NotFoundException;
import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;

public class WaterFixMod implements WurmServerMod, PreInitable
{
    @Override
    public void preInit()
    {
        fixUnderwaterCheck();
    }

    private void fixUnderwaterCheck()
    {
        try
        {
            ClassPool classPool = HookManager.getInstance().getClassPool();
            CtClass terraforming = classPool.get("com.wurmonline.server.behaviours.Terraforming");
            CtClass[] paramTypes = {
                CtPrimitiveType.intType,
                CtPrimitiveType.intType,
                CtPrimitiveType.booleanType
            };
            CtMethod method = terraforming.getMethod("isCornerUnderWater", Descriptor.ofMethod(CtPrimitiveType.booleanType, paramTypes));
            MethodInfo methodInfo = method.getMethodInfo();
            CodeAttribute codeAttr = methodInfo.getCodeAttribute();
            CodeIterator codeIter = codeAttr.iterator();
            LocalVariableAttribute locals = (LocalVariableAttribute)codeAttr.getAttribute(LocalVariableAttribute.tag);
            List<Integer> heightIndices = IntStream
                .range(0, locals.tableLength())
                .filter(x -> locals.variableName(x).equals("h"))
                .boxed()
                .collect(Collectors.toList())
            ;

            if(!heightIndices.stream().findFirst().isPresent())
                throw new HookException("Could not find height variable(s)");

            int repeat = 0;
            boolean mark = false;

            while(codeIter.hasNext())
            {
                int index = codeIter.next();
                int op = codeIter.byteAt(index);

                if(mark)
                {
                    if(op == CodeIterator.IFGT)
                    {
                        codeIter.writeByte(CodeIterator.IFGE, index);

                        mark = false;
                        repeat++;

                        if(repeat >= 2)
                            break;
                    }

                    continue;
                }

                boolean isUnpacked = op == CodeIterator.ILOAD;
                boolean isPacked = op >= CodeIterator.ILOAD_0 && op <= CodeIterator.ILOAD_3;

                if(isPacked || isUnpacked)
                {
                    int localIndex = isPacked ? iloadUnpackedValue(op) : codeIter.byteAt(index + 1);

                    if(heightIndices.stream().anyMatch(x -> x == localIndex))
                        mark = true;
                }
            }
        }
        catch(NotFoundException | BadBytecode err)
        {
            throw new HookException(err);
        }
    }

    private int iloadUnpackedValue(int op)
    {
        switch(op)
        {
            case CodeIterator.ILOAD_0:
                return 0;
            case CodeIterator.ILOAD_1:
                return 1;
            case CodeIterator.ILOAD_2:
                return 2;
            case CodeIterator.ILOAD_3:
                return 3;
            default:
                throw new RuntimeException(String.format("Bytecode %02X is not a packed ILOAD", op));
        }
    }
}
