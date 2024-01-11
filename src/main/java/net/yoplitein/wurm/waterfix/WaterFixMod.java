package net.yoplitein.wurm.waterfix;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javassist.bytecode.BadBytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.LocalVariableAttribute;
import javassist.bytecode.MethodInfo;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;

import com.wurmonline.mesh.Tiles;
import com.wurmonline.mesh.Tiles.TileBorderDirection;
import com.wurmonline.server.Server;

public class WaterFixMod implements WurmServerMod, PreInitable
{
    @Override
    public void preInit()
    {
        fixUnderwaterCheck();
        fixHedgeCheck();
    }

    private void fixUnderwaterCheck()
    {
        try
        {
            ClassPool classPool = HookManager.getInstance().getClassPool();
            CtClass terraforming = classPool.get("com.wurmonline.server.behaviours.Terraforming");
            CtMethod method = terraforming.getDeclaredMethod("isCornerUnderWater");
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
            boolean foundHeightLocal = false;

            while(codeIter.hasNext())
            {
                int index = codeIter.next();
                int op = codeIter.byteAt(index);

                if(foundHeightLocal)
                {
                    if(op == CodeIterator.IFGT)
                    {
                        codeIter.writeByte(CodeIterator.IFGE, index);

                        foundHeightLocal = false;
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
                        foundHeightLocal = true;
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

    private void fixHedgeCheck()
    {
        try
        {
            ClassPool classPool = HookManager.getInstance().getClassPool();
            CtClass terraforming = classPool.getCtClass("com.wurmonline.server.behaviours.Terraforming");
            ExprEditor editor = new ExprEditor() {
                @Override
                public void edit(MethodCall call) throws CannotCompileException {
                    if(call.getMethodName().equals("isCornerUnderWater")) {
                        call.replace("{$_ = net.yoplitein.wurm.waterfix.WaterFixMod.isEdgeUnderWater(tilex, tiley, dir);}");
                    }
                }
            };

            CtMethod plantHedgeMethod = terraforming.getDeclaredMethod("plantHedge");
            plantHedgeMethod.instrument(editor);

            CtMethod plantFlowerbedMethod = terraforming.getDeclaredMethod("plantFlowerbed");
            plantFlowerbedMethod.instrument(editor);
        }
        catch(NotFoundException | CannotCompileException err)
        {
            throw new HookException(err);
        }
    }

    public static boolean isEdgeUnderWater(int tilex, int tiley, TileBorderDirection dir) {

        short height = Tiles.decodeHeight(Server.surfaceMesh.getTile(tilex, tiley));
        if(height < 0)
            return true;

        int dx = 0;
        int dy = 0;
        if(dir == TileBorderDirection.DIR_HORIZ)
            dx = 1;
        else
            dy = 1;
        height = Tiles.decodeHeight(Server.surfaceMesh.getTile(tilex + dx, tiley + dy));
        return height < 0;
    }
}
