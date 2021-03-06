package com.pro;

import java.util.Iterator;
import java.util.Map;

import soot.Body;
import soot.BodyTransformer;
import soot.Local;
import soot.LongType;
import soot.Modifier;
import soot.PackManager;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Transform;
import soot.Unit;
import soot.jimple.AssignStmt;
import soot.jimple.GotoStmt;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.LongConstant;
import soot.jimple.ReturnStmt;
import soot.jimple.ReturnVoidStmt;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.Stmt;
import soot.util.Chain;

/** 
   Example to instrument a classfile to produce goto counts. 
 */
public class MainInstrumenter
{    
    public static void main(String[] args) 
    {
        if(args.length == 0)
        {
            System.out.println("Syntax: java ashes.examples.countgotos.Main [soot options]");
            System.exit(0);
        }            
        
        PackManager.v().getPack("jtp").add(new Transform("jtp.instrumenter", GotoInstrumenter.v()));

	// Just in case, resolve the PrintStream and System SootClasses.
        Scene.v().addBasicClass("java.io.PrintStream",SootClass.SIGNATURES);
        Scene.v().addBasicClass("java.lang.System",SootClass.SIGNATURES);
        soot.Main.main(args);
    }
}

/**
    InstrumentClass example.
    
    Instruments the given class to print out the number of Jimple goto 
    statements executed.

    To enable this class, enable the given PackAdjuster by compiling it 
    separately, into the soot package.
 */

class GotoInstrumenter extends BodyTransformer
{
    private static GotoInstrumenter instance = new GotoInstrumenter();
    private GotoInstrumenter() {}

    public static GotoInstrumenter v() { return instance; }

    private boolean addedFieldToMainClassAndLoadedPrintStream = false;
    private SootClass javaIoPrintStream;

    private Local addTmpRef(Body body)
    {
        Local tmpRef = Jimple.v().newLocal("tmpRef", RefType.v("java.io.PrintStream"));
        body.getLocals().add(tmpRef);
        return tmpRef;
    }
     
    private Local addTmpLong(Body body)
    {
        Local tmpLong = Jimple.v().newLocal("tmpLong", LongType.v()); 
        body.getLocals().add(tmpLong);
        return tmpLong;
    }

    private void addStmtsToBefore(Chain<Unit> units, Stmt s, SootField gotoCounter, Local tmpRef, Local tmpLong)
    {
        // insert "tmpRef = java.lang.System.out;" 
        units.insertBefore(Jimple.v().newAssignStmt( 
                      tmpRef, Jimple.v().newStaticFieldRef( 
                      Scene.v().getField("<java.lang.System: java.io.PrintStream out>").makeRef())), s);

        // insert "tmpLong = gotoCounter;" 
        units.insertBefore(Jimple.v().newAssignStmt(tmpLong, 
                      Jimple.v().newStaticFieldRef(gotoCounter.makeRef())), s);
            
        // insert "tmpRef.println(tmpLong);" 
        SootMethod toCall = javaIoPrintStream.getMethod("void println(long)");                    
        units.insertBefore(Jimple.v().newInvokeStmt(
                      Jimple.v().newVirtualInvokeExpr(tmpRef, toCall.makeRef(), tmpLong)), s);
    }

    protected void internalTransform(Body body, String phaseName, Map options)
    {
        SootClass sClass = body.getMethod().getDeclaringClass();
        SootField gotoCounter = null;
        boolean addedLocals = false;
        Local tmpRef = null, tmpLong = null;
        Chain<Unit> units = body.getUnits();
        
        // Add code at the end of the main method to print out the 
        // gotoCounter (this only works in simple cases, because you may have multiple returns or System.exit()'s )
        synchronized(this)
        {
            if (!Scene.v().getMainClass().
                    declaresMethod("void main(java.lang.String[])"))
                throw new RuntimeException("couldn't find main() in mainClass");

            if (addedFieldToMainClassAndLoadedPrintStream)
                gotoCounter = Scene.v().getMainClass().getFieldByName("gotoCount");
            else
            {
                // Add gotoCounter field
                gotoCounter = new SootField("gotoCount", LongType.v(), 
                                                Modifier.STATIC);
                Scene.v().getMainClass().addField(gotoCounter);

                javaIoPrintStream = Scene.v().getSootClass("java.io.PrintStream");

                addedFieldToMainClassAndLoadedPrintStream = true;
            }
        }
            
        // Add code to increase goto counter each time a goto is encountered
        {
            boolean isMainMethod = body.getMethod().getSubSignature().equals("void main(java.lang.String[])");

            Local tmpLocal = Jimple.v().newLocal("tmp", LongType.v());
            body.getLocals().add(tmpLocal);
                
            Iterator<Unit> stmtIt = units.snapshotIterator();
            
            while(stmtIt.hasNext())
            {
                Stmt s = (Stmt) stmtIt.next();

                if(s instanceof GotoStmt)
                {
                    AssignStmt toAdd1 = Jimple.v().newAssignStmt(tmpLocal, 
                                 Jimple.v().newStaticFieldRef(gotoCounter.makeRef()));
                    AssignStmt toAdd2 = Jimple.v().newAssignStmt(tmpLocal,
                                 Jimple.v().newAddExpr(tmpLocal, LongConstant.v(1L)));
                    AssignStmt toAdd3 = Jimple.v().newAssignStmt(Jimple.v().newStaticFieldRef(gotoCounter.makeRef()), 
                                                                 tmpLocal);

                    // insert "tmpLocal = gotoCounter;"
                    units.insertBefore(toAdd1, s);
                        
                    // insert "tmpLocal = tmpLocal + 1L;" 
                    units.insertBefore(toAdd2, s);

                    // insert "gotoCounter = tmpLocal;" 
                    units.insertBefore(toAdd3, s);
                }
                else if (s instanceof InvokeStmt)
                {
                    InvokeExpr iexpr = (InvokeExpr) ((InvokeStmt)s).getInvokeExpr();
                    if (iexpr instanceof StaticInvokeExpr)
                    {
                        SootMethod target = ((StaticInvokeExpr)iexpr).getMethod();
                        
                        if (target.getSignature().equals("<java.lang.System: void exit(int)>"))
                        {
                            if (!addedLocals)
                            {
                                tmpRef = addTmpRef(body); tmpLong = addTmpLong(body);
                                addedLocals = true;
                            }
                            addStmtsToBefore(units, s, gotoCounter, tmpRef, tmpLong);
                        }
                    }
                }
                else if (isMainMethod && (s instanceof ReturnStmt || s instanceof ReturnVoidStmt))
                {
                    if (!addedLocals)
                    {
                        tmpRef = addTmpRef(body); tmpLong = addTmpLong(body);
                        addedLocals = true;
                    }
                    addStmtsToBefore(units, s, gotoCounter, tmpRef, tmpLong);
                }
            }
        }
    }
}