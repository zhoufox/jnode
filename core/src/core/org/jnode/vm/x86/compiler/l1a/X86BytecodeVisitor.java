/*
 * $Id$
 */
package org.jnode.vm.x86.compiler.l1a;

import org.jnode.assembler.Label;
import org.jnode.assembler.NativeStream;
import org.jnode.assembler.UnresolvedObjectRefException;
import org.jnode.assembler.x86.AbstractX86Stream;
import org.jnode.assembler.x86.Register;
import org.jnode.assembler.x86.X86Constants;
import org.jnode.vm.SoftByteCodes;
import org.jnode.vm.bytecode.BasicBlock;
import org.jnode.vm.bytecode.BytecodeParser;
import org.jnode.vm.classmgr.ObjectLayout;
import org.jnode.vm.classmgr.Signature;
import org.jnode.vm.classmgr.TIBLayout;
import org.jnode.vm.classmgr.VmArray;
import org.jnode.vm.classmgr.VmClassLoader;
import org.jnode.vm.classmgr.VmConstClass;
import org.jnode.vm.classmgr.VmConstFieldRef;
import org.jnode.vm.classmgr.VmConstIMethodRef;
import org.jnode.vm.classmgr.VmConstMethodRef;
import org.jnode.vm.classmgr.VmConstString;
import org.jnode.vm.classmgr.VmField;
import org.jnode.vm.classmgr.VmInstanceField;
import org.jnode.vm.classmgr.VmInstanceMethod;
import org.jnode.vm.classmgr.VmMethod;
import org.jnode.vm.classmgr.VmStaticField;
import org.jnode.vm.classmgr.VmStaticMethod;
import org.jnode.vm.classmgr.VmType;
import org.jnode.vm.classmgr.VmTypeState;
import org.jnode.vm.compiler.CompileError;
import org.jnode.vm.compiler.CompiledMethod;
import org.jnode.vm.compiler.InlineBytecodeVisitor;
import org.jnode.vm.x86.VmX86Architecture;
import org.jnode.vm.x86.compiler.X86CompilerConstants;
import org.jnode.vm.x86.compiler.X86CompilerContext;
import org.jnode.vm.x86.compiler.X86CompilerHelper;
import org.jnode.vm.x86.compiler.X86JumpTable;

/**
 * Actual converter from bytecodes to X86 native code. Uses a virtual stack to
 * delay item emission, as described in the ORP project
 * 
 * @author Ewout Prangsma (epr@users.sourceforge.net)
 * @author Patrik Reali
 *  
 */

//IMPROVE: use common pattern for all *aload instructions
class X86BytecodeVisitor extends InlineBytecodeVisitor implements
        X86CompilerConstants {

    /** The output stream */
    private final AbstractX86Stream os;

    /** Helper class */
    private final X86CompilerHelper helper;

    /** The destination compiled method */
    private final CompiledMethod cm;

    /** Label of current instruction */
    private Label curInstrLabel;

    /** Length of os at start of method */
    private int startOffset;

    /** Stackframe utility */
    private X86StackFrame stackFrame;

    /** Size of an object reference */
    private final int slotSize;

    /** Current context */
    private final X86CompilerContext context;

    /** Emit logging info */
    private final boolean log;

    /** Class loader */
    private VmClassLoader loader;

    private boolean startOfBB;

    private Label endOfInlineLabel;

    private int maxLocals;

    private EmitterContext eContext;

    /*
     * Virtual Stack: this stack contains values that have been computed but
     * not emitted yet; emission is delayed to allow for optimizations, in
     * particular using registers instead of stack operations.
     * 
     * The vstack is valid only inside a basic block; items in the stack are
     * flushed at the end of the basic block.
     * 
     * Aliasing: modifying a value that is still on the stack is forbidden.
     * Each time a local is assigned, the stack is checked for aliases. For teh
     * same reason, array and field operations are not delayed.
     */
    private VirtualStack vstack;

    /**
     * Create a new instance
     * 
     * @param outputStream
     * @param cm
     * @param isBootstrap
     * @param context
     */
    public X86BytecodeVisitor(NativeStream outputStream, CompiledMethod cm,
            boolean isBootstrap, X86CompilerContext context) {
        this.os = (AbstractX86Stream) outputStream;
        this.context = context;
        this.helper = new X86CompilerHelper(os, context, isBootstrap);
        this.cm = cm;
        this.slotSize = VmX86Architecture.SLOT_SIZE;
        this.log = os.isLogEnabled();
        this.eContext = new EmitterContext(this.os, this.helper);
    }

    /**
     * @param method
     * @see org.jnode.vm.bytecode.BytecodeVisitor#startMethod(org.jnode.vm.classmgr.VmMethod)
     */
    public void startMethod(VmMethod method) {
        this.maxLocals = method.getBytecode().getNoLocals();
        this.loader = method.getDeclaringClass().getLoader();
        helper.setMethod(method);
        this.startOffset = os.getLength();
        this.stackFrame = new X86StackFrame(os, helper, method, context, cm);
        stackFrame.emitHeader();
    }

    /**
     * The given basic block is about to start.
     */
    public void startBasicBlock(BasicBlock bb) {
        if (log) {
            os.log("Start of basic block " + bb);
        }
        helper.reset();
        startOfBB = true;
        this.vstack = new VirtualStack();
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#startInstruction(int)
     */
    public void startInstruction(int address) {
        //TODO: port to orp-style
        this.curInstrLabel = helper.getInstrLabel(address);
        if (startOfBB) {
            os.setObjectRef(curInstrLabel);
            startOfBB = false;
        }
        final int offset = os.getLength() - startOffset;
        cm.add(address, offset);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#endInstruction()
     */
    public void endInstruction() {
        //TODO: port to orp-style
        // Nothing to do here
    }

    /**
     * The started basic block has finished.
     */
    public void endBasicBlock() {
        //TODO: flush vstack
        if (log) {
            os.log("End of basic block");
        }
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#endMethod()
     */
    public void endMethod() {
        //TODO: port to orp-style
        stackFrame.emitTrailer(maxLocals);
    }

    /**
     * @param parser
     * @see org.jnode.vm.bytecode.BytecodeVisitor#setParser(org.jnode.vm.bytecode.BytecodeParser)
     */
    public void setParser(BytecodeParser parser) {
        // Nothing to do here
    }

    private void assertCondition(boolean cond) {
        if (!cond) throw new Error("assert failed");
    }

    //	private void NotImplemented() {
    //		throw new Error("NotImplemented");
    //	}

    private void OpcodeNotImplemented(String name) {
        System.out.println(name + " not implemented");
    }

    private void PrepareForOperation(Item destAndSource, Item source) {
        // WARNING: source was on top of the virtual stack (thus higher than
        // destAndSource)
        // x86 can only deal with one complex argument
        // destAndSource must be a register
        source.loadIf(eContext, (Item.STACK | Item.FREGISTER));
        destAndSource.load(eContext);
    }

    /**
     * @see org.jnode.vm.compiler.InlineBytecodeVisitor#endInlinedMethod(org.jnode.vm.classmgr.VmMethod)
     */
    public void endInlinedMethod(VmMethod previousMethod) {
        //TODO: port to orp-style
        helper.setMethod(previousMethod);
        os.setObjectRef(endOfInlineLabel);
    }

    /**
     * @see org.jnode.vm.compiler.InlineBytecodeVisitor#startInlinedMethod(org.jnode.vm.classmgr.VmMethod,
     *      int)
     */
    public void startInlinedMethod(VmMethod inlinedMethod, int newMaxLocals) {
        //TODO: port to orp-style
        maxLocals = newMaxLocals;
        endOfInlineLabel = new Label(curInstrLabel + "_end_of_inline");
        helper.startInlinedMethod(inlinedMethod, curInstrLabel);
    }

    /**
     * @see org.jnode.vm.compiler.InlineBytecodeVisitor#visit_inlinedReturn()
     */
    public void visit_inlinedReturn() {
        //TODO: port to orp-style
        os.writeJMP(endOfInlineLabel);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_aaload()
     */
    public final void visit_aaload() {
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        Register refReg = ref.getRegister();
        checkBounds(ref, idx);
        if (idx.getKind() == Item.CONSTANT) {
            final int offset = idx.getValue();
            os.writeMOV(INTSIZE, refReg, refReg, offset + VmArray.DATA_OFFSET
                    * 4);
        } else {
            os.writeMOV(INTSIZE, refReg, refReg, idx.getRegister(), 4,
                    VmArray.DATA_OFFSET * 4);
            idx.release(eContext);
        }
        // do not release ref, it is recycled into the result
        vstack.pushItem(RefItem.createRegister(refReg));

        //		helper.writePOP(T0); // Index
        //		helper.writePOP(S0); // Arrayref
        //		checkBounds(S0, T0);
        //		os.writePUSH(S0, T0, 4, VmArray.DATA_OFFSET * 4);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_aastore()
     */
    public final void visit_aastore() {
        RefItem val = vstack.popRef();
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();

        //IMPROVE: optimize case with const value
        val.load(eContext);
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        Register r = ref.getRegister();

        checkBounds(ref, idx);
        if (idx.getKind() == Item.CONSTANT) {
            int i = idx.getValue();
            os.writeMOV(INTSIZE, r, i + VmArray.DATA_OFFSET * 4, val
                    .getRegister());
        } else {
            os.writeMOV(INTSIZE, r, idx.getRegister(), 4,
                    VmArray.DATA_OFFSET * 4, val.getRegister());
        }
        val.release(eContext);
        idx.release(eContext);
        ref.release(eContext);

        //TODO: port write barrier
        helper.writeArrayStoreWriteBarrier(S0, T0, T1, S1);

        //		helper.writePOP(T1); // Value
        //		helper.writePOP(T0); // Index
        //		helper.writePOP(S0); // Arrayref
        //		checkBounds(S0, T0);
        //		os.writeMOV(INTSIZE, S0, T0, 4, VmArray.DATA_OFFSET * 4, T1);
        //		helper.writeArrayStoreWriteBarrier(context, S0, T0, T1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_aconst_null()
     */
    public final void visit_aconst_null() {
        vstack.pushItem(RefItem.createConst(null));
        //		os.writeMOV_Const(T0, 0);
        //		helper.writePUSH(T0);
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_aload(int)
     */
    public final void visit_aload(int index) {
        vstack.pushItem(RefItem.createLocal(stackFrame.getEbpOffset(index)));
        //		final int ebpOfs = stackFrame.getEbpOffset(index);
        //		os.writeMOV(INTSIZE, T0, FP, ebpOfs);
        //		helper.writePUSH(T0);
    }

    /**
     * @param classRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_anewarray(org.jnode.vm.classmgr.VmConstClass)
     */
    public final void visit_anewarray(VmConstClass classRef) {
        //TODO: port to orp-style
        OpcodeNotImplemented("anewarray");
        vstack.push(eContext);

        writeResolveAndLoadClassToEAX(classRef, S0);
        helper.writePOP(S0); /* Count */

        stackFrame.writePushMethodRef();
        helper.writePUSH(EAX); /* Class */
        helper.writePUSH(S0); /* Count */
        helper.invokeJavaMethod(context.getAnewarrayMethod());
        /* Result is already push on the stack */

        vstack.pushItem(RefItem.createStack());
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_areturn()
     */
    public final void visit_areturn() {
        vstack.requestRegister(eContext, EAX);
        RefItem i = vstack.popRef();
        i.loadTo(eContext, EAX);

        //		helper.writePOP(T0);
        visit_return();
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_arraylength()
     */
    public final void visit_arraylength() {
        //TODO: port to orp-style
        OpcodeNotImplemented("arraylength");
        vstack.push(eContext);
        RefItem ref = vstack.popRef();

        helper.writePOP(T0); // Arrayref
        os.writeMOV(INTSIZE, T1, T0, VmArray.LENGTH_OFFSET * slotSize);
        helper.writePUSH(T1);

        ref.release(eContext);
        vstack.pushItem(IntItem.createStack());
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_astore(int)
     */
    public final void visit_astore(int index) {
        int disp = stackFrame.getEbpOffset(index);
        RefItem i = vstack.popRef();
        i.loadIf(eContext, ~Item.CONSTANT);

        if (i.getKind() == Item.CONSTANT) {
            //TODO: check whether constant is different from NULL (loaded with
            // ldc)
            os.writeMOV_Const(FP, disp, 0);
        } else {
            os.writeMOV(INTSIZE, FP, disp, i.getRegister());
            i.release(eContext);
        }
        //		final int ebpOfs = stackFrame.getEbpOffset(index);
        //		helper.writePOP(FP, ebpOfs);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_athrow()
     */
    public final void visit_athrow() {
        vstack.requestRegister(eContext, EAX);
        RefItem ref = vstack.popRef();
        ref.loadTo(eContext, EAX);

        //		helper.writePOP(T0); // Exception
        helper.writeJumpTableCALL(X86JumpTable.VM_ATHROW_OFS);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_baload()
     */
    public final void visit_baload() {
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        Register refReg = ref.getRegister();
        checkBounds(ref, idx);
        if (idx.getKind() == Item.CONSTANT) {
            final int offset = idx.getValue();
            os.writeMOV(BYTESIZE, refReg, refReg, offset + VmArray.DATA_OFFSET
                    * 4);
        } else {
            os.writeMOV(BYTESIZE, refReg, refReg, idx.getRegister(), 1,
                    VmArray.DATA_OFFSET * 4);
            idx.release(eContext);
        }
        os.writeMOVSX(refReg, refReg, BYTESIZE);
        // do not release ref, it is recycled into the result
        vstack.pushItem(IntItem.createRegister(refReg));

        //		helper.writePOP(T1); // Index
        //		helper.writePOP(S0); // Arrayref
        //		checkBounds(S0, T1);
        //		os.writeMOV(BYTESIZE, T0, S0, T1, 1, VmArray.DATA_OFFSET * 4);
        //		os.writeMOVSX(T0, T0, BYTESIZE);
        //		helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_bastore()
     */
    public final void visit_bastore() {
        IntItem val = vstack.popInt();
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();

        //IMPROVE: optimize case with const value
        val.load(eContext);
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        Register r = ref.getRegister();

        checkBounds(ref, idx);
        if (idx.getKind() == Item.CONSTANT) {
            int i = idx.getValue();
            os.writeMOV(BYTESIZE, r, i + VmArray.DATA_OFFSET * 4, val
                    .getRegister());
        } else {
            os.writeMOV(BYTESIZE, r, idx.getRegister(), 4,
                    VmArray.DATA_OFFSET * 4, val.getRegister());
        }
        val.release(eContext);
        idx.release(eContext);
        ref.release(eContext);

        //		helper.writePOP(T1); // Value
        //		helper.writePOP(T0); // Index
        //		helper.writePOP(S0); // Arrayref
        //		checkBounds(S0, T0);
        //		os.writeMOV(BYTESIZE, S0, T0, 1, VmArray.DATA_OFFSET * 4, T1);
    }

    //	/**
    //	 * @param value
    //	 * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_bipush(byte)
    //	 */
    //	public final void visit_bipush(byte value) {
    //		vstack.pushConstant(Item.INT, Constant.getInstance(value));
    //// os.writeMOV_Const(T0, value);
    //// //os.writeMOVSX(T0, T0, BYTESIZE);
    //// helper.writePUSH(T0);
    //	}

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_caload()
     */
    public final void visit_caload() {
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        Register refReg = ref.getRegister();
        checkBounds(ref, idx);
        if (idx.getKind() == Item.CONSTANT) {
            final int offset = idx.getValue();
            os.writeMOV(WORDSIZE, refReg, refReg, offset + VmArray.DATA_OFFSET
                    * 4);
        } else {
            os.writeMOV(WORDSIZE, refReg, refReg, idx.getRegister(), 2,
                    VmArray.DATA_OFFSET * 4);
            idx.release(eContext);
        }
        os.writeMOVZX(refReg, refReg, WORDSIZE);
        // do not release ref, it is recycled into the result
        vstack.pushItem(IntItem.createRegister(refReg));

        //		helper.writePOP(T1); // Index
        //		helper.writePOP(S0); // Arrayref
        //		checkBounds(S0, T1);
        //		os.writeMOV(WORDSIZE, T0, S0, T1, 2, VmArray.DATA_OFFSET * 4);
        //		os.writeMOVZX(T0, T0, WORDSIZE);
        //		helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_castore()
     */
    public final void visit_castore() {
        IntItem val = vstack.popInt();
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();

        //IMPROVE: optimize case with const value
        val.load(eContext);
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        Register r = ref.getRegister();

        checkBounds(ref, idx);
        if (idx.getKind() == Item.CONSTANT) {
            int i = idx.getValue();
            os.writeMOV(WORDSIZE, r, i + VmArray.DATA_OFFSET * 4, val
                    .getRegister());
        } else {
            os.writeMOV(WORDSIZE, r, idx.getRegister(), 2,
                    VmArray.DATA_OFFSET * 4, val.getRegister());
        }
        val.release(eContext);
        idx.release(eContext);
        ref.release(eContext);

        //		helper.writePOP(T1); // Value
        //		helper.writePOP(T0); // Index
        //		helper.writePOP(S0); // Arrayref
        //		checkBounds(S0, T0);
        //		os.writeMOV(WORDSIZE, S0, T0, 2, VmArray.DATA_OFFSET * 4, T1);
    }

    /**
     * @param classRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_checkcast(org.jnode.vm.classmgr.VmConstClass)
     */
    public final void visit_checkcast(VmConstClass classRef) {
        //TODO: port to orp-style
        OpcodeNotImplemented("checkcast");
        vstack.push(eContext);
        RefItem ref = vstack.popRef();
        vstack.pushItem(ref);

        writeResolveAndLoadClassToEAX(classRef, S0);

        final Label okLabel = new Label(this.curInstrLabel + "cc-ok");

        /* objectref -> ECX (also leave in on the stack */
        os.writeMOV(INTSIZE, ECX, SP, 0);
        /* Is objectref null? */
        os.writeTEST(ECX, ECX);
        os.writeJCC(okLabel, X86Constants.JZ);
        /* Is instanceof? */
        instanceOf(okLabel);
        /* Not instanceof */

        // Call SoftByteCodes.systemException
        helper.writePUSH(SoftByteCodes.EX_CLASSCAST);
        helper.writePUSH(0);
        helper.invokeJavaMethod(context.getSystemExceptionMethod());

        /* Exception in EAX, throw it */
        helper.writeJumpTableCALL(X86JumpTable.VM_ATHROW_OFS);

        /* Normal exit */
        os.setObjectRef(okLabel);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_d2f()
     */
    public final void visit_d2f() {
        //TODO: port to orp-style
        OpcodeNotImplemented("d2f");
        vstack.push(eContext);
        Item v = vstack.popItem(Item.DOUBLE);
        v.release(eContext);
        vstack.pushItem(FloatItem.createStack());

        os.writeFLD64(SP, 0);
        os.writeLEA(SP, SP, 4);
        os.writeFSTP32(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_d2i()
     */
    public final void visit_d2i() {
        //TODO: port to orp-style
        OpcodeNotImplemented("d2i");
        vstack.push(eContext);
        Item v = vstack.popItem(Item.DOUBLE);
        v.release(eContext);
        vstack.pushItem(IntItem.createStack());

        os.writeFLD64(SP, 0);
        os.writeLEA(SP, SP, 4);
        os.writeFISTP32(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_d2l()
     */
    public final void visit_d2l() {
        //TODO: port to orp-style
        OpcodeNotImplemented("d2l");
        vstack.push(eContext);
        Item v = vstack.popItem(Item.DOUBLE);
        v.release(eContext);
        vstack.pushItem(LongItem.createStack());

        os.writeFLD64(SP, 0);
        os.writeFISTP64(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dadd()
     */
    public final void visit_dadd() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dadd");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.DOUBLE);
        Item v2 = vstack.popItem(Item.DOUBLE);
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        os.writeFLD64(SP, 8);
        os.writeFADD64(SP, 0);
        os.writeLEA(SP, SP, 8);
        os.writeFSTP64(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_daload()
     */
    public final void visit_daload() {
        //TODO: port to orp-style
        OpcodeNotImplemented("daload");
        vstack.push(eContext);
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        ref.release(eContext);
        idx.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        helper.writePOP(T0); // Index
        helper.writePOP(S0); // Arrayref
        checkBounds(S0, T0);
        os.writeLEA(S0, S0, T0, 8, VmArray.DATA_OFFSET * 4);
        os.writeMOV(INTSIZE, T0, S0, 0);
        os.writeMOV(INTSIZE, T1, S0, 4);
        helper.writePUSH64(T0, T1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dastore()
     */
    public final void visit_dastore() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dastore");
        vstack.push(eContext);
        Item val = vstack.popItem(Item.DOUBLE);
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        val.release(eContext);
        idx.release(eContext);
        ref.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        os.writeMOV(INTSIZE, T0, SP, 8); // Index
        os.writeMOV(INTSIZE, S0, SP, 12); // Arrayref
        checkBounds(S0, T0);
        os.writeLEA(S0, S0, T0, 8, VmArray.DATA_OFFSET * 4);
        helper.writePOP64(T0, T1); // Value
        os.writeMOV(INTSIZE, S0, 0, T0);
        os.writeMOV(INTSIZE, S0, 4, T1);
        os.writeLEA(SP, SP, 8); // Remove index, arrayref
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dcmpg()
     */
    public final void visit_dcmpg() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dcmpg");
        vstack.push(eContext);

        visit_dfcmp(true, false);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dcmpl()
     */
    public final void visit_dcmpl() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dcmp;");
        vstack.push(eContext);

        visit_dfcmp(false, false);
    }

    /**
     * @param value
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dconst(double)
     */
    public final void visit_dconst(double value) {
        vstack.pushItem(DoubleItem.createConst(value));
        //		visit_lconst(Double.doubleToLongBits(value));
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ddiv()
     */
    public final void visit_ddiv() {
        //TODO: port to orp-style
        OpcodeNotImplemented("ddiv");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.DOUBLE);
        Item v2 = vstack.popItem(Item.DOUBLE);
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        os.writeFLD64(SP, 8);
        os.writeFDIV64(SP, 0);
        os.writeLEA(SP, SP, 8);
        os.writeFSTP64(SP, 0);
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dload(int)
     */
    public final void visit_dload(int index) {
        vstack.pushItem(DoubleItem.createLocal(stackFrame.getEbpOffset(index)));
        //		int ebpOfs = stackFrame.getWideEbpOffset(index);
        //		os.writeMOV(INTSIZE, Register.EAX, FP, ebpOfs);
        //		os.writeMOV(INTSIZE, Register.EDX, FP, ebpOfs + 4);
        //		helper.writePUSH64(Register.EAX, Register.EDX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dmul()
     */
    public final void visit_dmul() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dmul");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.DOUBLE);
        Item v2 = vstack.popItem(Item.DOUBLE);
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        os.writeFLD64(SP, 8);
        os.writeFMUL64(SP, 0);
        os.writeLEA(SP, SP, 8);
        os.writeFSTP64(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dneg()
     */
    public final void visit_dneg() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dneg");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.DOUBLE);
        v1.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        os.writeFLD64(SP, 0);
        os.writeFCHS();
        os.writeFSTP64(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_drem()
     */
    public final void visit_drem() {
        //TODO: port to orp-style
        OpcodeNotImplemented("drem");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.DOUBLE);
        Item v2 = vstack.popItem(Item.DOUBLE);
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        os.writeFLD64(SP, 8);
        os.writeFLD64(SP, 0);
        os.writeFPREM();
        os.writeLEA(SP, SP, 8);
        os.writeFSTP64(SP, 0);
        //		|os.writeFFREE(Register.ST0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dreturn()
     */
    public final void visit_dreturn() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dreturn");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.DOUBLE);
        v1.release(eContext);

        helper.writePOP64(Register.EAX, Register.EDX);
        visit_return();
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dstore(int)
     */
    public final void visit_dstore(int index) {
        //TODO: port to orp-style
        OpcodeNotImplemented("dstore");
        Item val = vstack.popItem(Item.DOUBLE);
        val.push(eContext);

        int ebpOfs = stackFrame.getWideEbpOffset(index);
        helper.writePOP64(Register.EAX, Register.EDX);
        os.writeMOV(INTSIZE, FP, ebpOfs, Register.EAX);
        os.writeMOV(INTSIZE, FP, ebpOfs + 4, Register.EDX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dsub()
     */
    public final void visit_dsub() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dsub");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.DOUBLE);
        Item v2 = vstack.popItem(Item.DOUBLE);
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        os.writeFLD64(SP, 8);
        os.writeFSUB64(SP, 0);
        os.writeLEA(SP, SP, 8);
        os.writeFSTP64(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dup_x1()
     */
    public final void visit_dup_x1() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dup x1");
        os.log("dup_x1");
        vstack.push(eContext);
        Item v1 = vstack.popItem();
        Item v2 = vstack.popItem();
        vstack.pushItem(v1);
        vstack.pushItem(v2);
        vstack.pushItem(v1);

        helper.writePOP(T0); // Value1
        helper.writePOP(S0); // Value2
        helper.writePUSH(T0); // Value1
        helper.writePUSH(S0); // Value2
        helper.writePUSH(T0); // Value1
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dup_x2()
     */
    public final void visit_dup_x2() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dup x2");
        os.log("dup_x2");
        vstack.push(eContext);
        Item v1 = vstack.popItem();
        Item v2 = vstack.popItem();
        if (v2.getCategory() == 2) {
            // form2
            vstack.pushItem(v1);
            vstack.pushItem(v2);
            vstack.pushItem(v1);
        } else {
            // form1
            Item v3 = vstack.popItem();
            vstack.pushItem(v1);
            vstack.pushItem(v3);
            vstack.pushItem(v2);
            vstack.pushItem(v1);
        }

        helper.writePOP(T0); // Value1
        helper.writePOP(T1); // Value2
        helper.writePOP(S0); // Value3
        helper.writePUSH(T0); // Value1
        helper.writePUSH(S0); // Value3
        helper.writePUSH(T1); // Value2
        helper.writePUSH(T0); // Value1
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dup()
     */
    public final void visit_dup() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dup");
        os.log("dup");
        vstack.push(eContext);
        Item v1 = vstack.popItem();
        vstack.pushItem(v1);
        vstack.pushItem(v1);

        os.writeMOV(INTSIZE, T0, SP, 0); // Value1, leave on stack
        helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dup2_x1()
     */
    public final void visit_dup2_x1() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dup2 x1");
        os.log("dup2_x1");
        vstack.push(eContext);
        Item v1 = vstack.popItem();
        Item v2 = vstack.popItem();
        assertCondition(v2.getCategory() == 1);
        if (v1.getCategory() == 2) { // form2
            vstack.pushItem(v1);
            vstack.pushItem(v2);
            vstack.pushItem(v1);
        } else {
            Item v3 = vstack.popItem();
            vstack.pushItem(v2);
            vstack.pushItem(v1);
            vstack.pushItem(v3);
            vstack.pushItem(v2);
            vstack.pushItem(v1);
        }

        helper.writePOP(T0); // Value1
        helper.writePOP(T1); // Value2
        helper.writePOP(S0); // Value3
        helper.writePUSH(T1); // Value2
        helper.writePUSH(T0); // Value1
        helper.writePUSH(S0); // Value3
        helper.writePUSH(T1); // Value2
        helper.writePUSH(T0); // Value1
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dup2_x2()
     */
    public final void visit_dup2_x2() {
        //TODO: port to orp-style
        OpcodeNotImplemented("dup2 x2");
        os.log("dup2_x2");

        vstack.push(eContext);
        Item v1 = vstack.popItem();
        Item v2 = vstack.popItem();
        int c1 = v1.getCategory();
        int c2 = v2.getCategory();
        // cope with brain-dead definition from Sun (look-like somebody there
        // was to eager to optimize this and it landed in the compiler...
        if (c2 == 2) {
            // form 4
            assertCondition(c1 == 2);
            vstack.pushItem(v1);
            vstack.pushItem(v2);
            vstack.pushItem(v1);
        } else {
            Item v3 = vstack.popItem();
            int c3 = v3.getCategory();
            if (c1 == 2) {
                // form 2
                assertCondition(c3 == 1);
                vstack.pushItem(v1);
                vstack.pushItem(v3);
                vstack.pushItem(v2);
                vstack.pushItem(v1);
            } else if (c3 == 2) {
                // form 3
                vstack.pushItem(v2);
                vstack.pushItem(v1);
                vstack.pushItem(v3);
                vstack.pushItem(v2);
                vstack.pushItem(v1);
            } else {
                // form 1
                Item v4 = vstack.popItem();
                vstack.pushItem(v2);
                vstack.pushItem(v1);
                vstack.pushItem(v4);
                vstack.pushItem(v3);
                vstack.pushItem(v2);
                vstack.pushItem(v1);
            }
        }
        helper.writePOP(Register.EAX); // Value1
        helper.writePOP(Register.EBX); // Value2
        helper.writePOP(Register.ECX); // Value3
        helper.writePOP(Register.EDX); // Value4
        helper.writePUSH(Register.EBX); // Value2
        helper.writePUSH(Register.EAX); // Value1
        helper.writePUSH(Register.EDX); // Value4
        helper.writePUSH(Register.ECX); // Value3
        helper.writePUSH(Register.EBX); // Value2
        helper.writePUSH(Register.EAX); // Value1
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_dup2()
     */
    public final void visit_dup2() {
        //TODO: port to orp-style
        //REFACTORING: merge all duplication opcodes
        OpcodeNotImplemented("dup2");
        os.log("dup2");
        vstack.push(eContext);
        Item v1 = vstack.popItem();
        if (v1.getCategory() == 1) {
            // form1
            Item v2 = vstack.popItem();
            assertCondition(v2.getCategory() == 1);
            vstack.pushItem(v2);
            vstack.pushItem(v1);
            vstack.pushItem(v2);
            vstack.pushItem(v1);
        } else {
            vstack.pushItem(v1);
            vstack.pushItem(v1);
        }
        os.writeMOV(INTSIZE, T0, SP, 0); // value1
        os.writeMOV(INTSIZE, S0, SP, 4); // value2
        helper.writePUSH(S0);
        helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_f2d()
     */
    public final void visit_f2d() {
        //TODO: port to orp-style
        OpcodeNotImplemented("f2d");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        os.writeFLD32(SP, 0);
        os.writeLEA(SP, SP, -4);
        os.writeFSTP64(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_f2i()
     */
    public final void visit_f2i() {
        //TODO: port to orp-style
        OpcodeNotImplemented("f2i");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);
        vstack.pushItem(IntItem.createStack());

        os.writeFLD32(SP, 0);
        os.writeFISTP32(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_f2l()
     */
    public final void visit_f2l() {
        //TODO: port to orp-style
        OpcodeNotImplemented("f2l");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        os.writeFLD32(SP, 0);
        os.writeLEA(SP, SP, -4);
        os.writeFISTP64(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fadd()
     */
    public final void visit_fadd() {
        //TODO: port to orp-style
        OpcodeNotImplemented("fadd");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        Item v2 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(FloatItem.createStack());
        os.writeFLD32(SP, 4);
        os.writeFADD32(SP, 0);
        os.writeLEA(SP, SP, 4);
        os.writeFSTP32(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_faload()
     */
    public final void visit_faload() {
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        Register refReg = ref.getRegister();
        checkBounds(ref, idx);
        //IMPROVE: load to ST
        if (idx.getKind() == Item.CONSTANT) {
            final int offset = idx.getValue();
            //os.writeMOV(INTSIZE, refReg, refReg, offset+VmArray.DATA_OFFSET
            // * 4);
            os.writePUSH(refReg, offset + VmArray.DATA_OFFSET * 4);
            idx.release(eContext);
        } else {
            //os.writeMOV(INTSIZE, refReg, refReg, idx.getRegister(), 2,
            // VmArray.DATA_OFFSET * 4);
            os.writePUSH(refReg, idx.getRegister(), 2, VmArray.DATA_OFFSET * 4);
            ref.release(eContext);
            idx.release(eContext);
        }

        // do not release ref, it is recycled into the result
        vstack.pushItem(FloatItem.createStack());

        //		helper.writePOP(T0); // Index
        //		helper.writePOP(S0); // Arrayref
        //		checkBounds(S0, T0);
        //		os.writeMOV(INTSIZE, T0, S0, T0, 4, VmArray.DATA_OFFSET * 4);
        //		helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fastore()
     */
    public final void visit_fastore() {
        //TODO: port to orp-style
        OpcodeNotImplemented("fastore");
        vstack.push(eContext);
        Item val = vstack.popItem(Item.FLOAT);
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        val.release(eContext);
        idx.release(eContext);
        ref.release(eContext);

        helper.writePOP(T1); // Value
        helper.writePOP(T0); // Index
        helper.writePOP(S0); // Arrayref
        checkBounds(S0, T0);
        os.writeMOV(INTSIZE, S0, T0, 4, VmArray.DATA_OFFSET * 4, T1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fcmpg()
     */
    public final void visit_fcmpg() {
        visit_dfcmp(true, true);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fcmpl()
     */
    public final void visit_fcmpl() {
        visit_dfcmp(false, true);
    }

    private void visit_dfcmp(boolean gt, boolean isfloat) {
        //TODO: port to orp-style
        OpcodeNotImplemented("dfcmp");
        vstack.push(eContext);
        Item v1 = vstack.popItem(isfloat ? Item.FLOAT : Item.DOUBLE);
        Item v2 = vstack.popItem(isfloat ? Item.FLOAT : Item.DOUBLE);
        v1.release(eContext);
        v2.release(eContext);

        if (isfloat) {
            if (gt) {
                os.writeFLD32(SP, 4); // reverse order
                os.writeFLD32(SP, 0);
            } else {
                os.writeFLD32(SP, 0);
                os.writeFLD32(SP, 4);
            }
            os.writeLEA(SP, SP, 8);
        } else {
            if (gt) {
                os.writeFLD64(SP, 8); // reverse order
                os.writeFLD64(SP, 0);
            } else {
                os.writeFLD64(SP, 0);
                os.writeFLD64(SP, 8);
            }
            os.writeLEA(SP, SP, 16);
        }
        os.writeFUCOMPP(); // Compare, Pop twice
        os.writeFNSTSW_AX(); // Store fp status word in AX
        os.writeSAHF(); // Store AH to Flags
        Label eqLabel = new Label(this.curInstrLabel + "eq");
        Label ltLabel = new Label(this.curInstrLabel + "lt");
        Label endLabel = new Label(this.curInstrLabel + "end");
        os.writeJCC(eqLabel, X86Constants.JE);
        os.writeJCC(ltLabel, X86Constants.JB);
        // Greater
        if (gt) {
            os.writeMOV_Const(Register.ECX, -1);
        } else {
            os.writeMOV_Const(Register.ECX, 1);
        }
        os.writeJMP(endLabel);
        // Equal
        os.setObjectRef(eqLabel);
        os.writeXOR(Register.ECX, Register.ECX);
        os.writeJMP(endLabel);
        // Less
        os.setObjectRef(ltLabel);
        if (gt) {
            os.writeMOV_Const(Register.ECX, 1);
        } else {
            os.writeMOV_Const(Register.ECX, -1);
        }
        // End
        os.setObjectRef(endLabel);
        helper.writePUSH(Register.ECX);
    }

    /**
     * @param value
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fconst(float)
     */
    public final void visit_fconst(float value) {
        vstack.pushItem(FloatItem.createConst(value));
        //		os.writeMOV_Const(Register.EAX, Float.floatToIntBits(value));
        //		helper.writePUSH(Register.EAX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fdiv()
     */
    public final void visit_fdiv() {
        //TODO: port to orp-style
        OpcodeNotImplemented("fdiv");
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        Item v2 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(FloatItem.createStack());

        os.writeFLD32(SP, 4);
        os.writeFDIV32(SP, 0);
        os.writeLEA(SP, SP, 4);
        os.writeFSTP32(SP, 0);
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fload(int)
     */
    public final void visit_fload(int index) {
        vstack.pushItem(FloatItem.createLocal(stackFrame.getEbpOffset(index)));
        //		final int ebpOfs = stackFrame.getEbpOffset(index);
        //		os.writeMOV(INTSIZE, T0, FP, ebpOfs);
        //		helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fmul()
     */
    public final void visit_fmul() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        Item v2 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(FloatItem.createStack());

        os.writeFLD32(SP, 4);
        os.writeFMUL32(SP, 0);
        os.writeLEA(SP, SP, 4);
        os.writeFSTP32(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fneg()
     */
    public final void visit_fneg() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);
        vstack.pushItem(FloatItem.createStack());

        os.writeFLD32(SP, 0);
        os.writeFCHS();
        os.writeFSTP32(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_frem()
     */
    public final void visit_frem() {
        //TODO: port to orp-style
        // reverse because pushing on fp stack
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        Item v2 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(FloatItem.createStack());

        os.writeFLD32(SP, 0);
        os.writeFLD32(SP, 4);
        os.writeFPREM();
        os.writeLEA(SP, SP, 4);
        os.writeFSTP32(SP, 0);
        os.writeFFREE(Register.ST0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_freturn()
     */
    public final void visit_freturn() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);
        helper.writePOP(Register.EAX);
        visit_return();
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fstore(int)
     */
    public final void visit_fstore(int index) {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);

        int ebpOfs = stackFrame.getEbpOffset(index);
        helper.writePOP(FP, ebpOfs);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_fsub()
     */
    public final void visit_fsub() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v1 = vstack.popItem(Item.FLOAT);
        Item v2 = vstack.popItem(Item.FLOAT);
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(FloatItem.createStack());

        os.writeFLD32(SP, 4);
        os.writeFSUB32(SP, 0);
        os.writeLEA(SP, SP, 4);
        os.writeFSTP32(SP, 0);
    }

    /**
     * @param fieldRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_getfield(org.jnode.vm.classmgr.VmConstFieldRef)
     */
    public final void visit_getfield(VmConstFieldRef fieldRef) {
        //TODO: port to orp-style; must probably implement a getfield method
        // in each Item!
        fieldRef.resolve(loader);
        final VmField field = fieldRef.getResolvedVmField();
        if (field.isStatic()) { throw new IncompatibleClassChangeError(
                "getfield called on static field " + fieldRef.getName()); }
        final VmInstanceField inf = (VmInstanceField) field;
        final int offset = inf.getOffset();

        int type = Item.SignatureToType(fieldRef.getSignature().charAt(0));
        vstack.push(eContext);
        RefItem ref = vstack.popRef();
        ref.release(eContext);
        vstack.pushStack(type);

        /** Objectref -> EBX */
        helper.writePOP(S0); // Objectref
        /* Get the data */
        if (!fieldRef.isWide()) {
            os.writeMOV(INTSIZE, T0, S0, offset);
            os.writePUSH(T0);
        } else {
            os.writeMOV(INTSIZE, T1, S0, offset + 4); // MSB
            os.writeMOV(INTSIZE, T0, S0, offset); // LSB
            helper.writePUSH64(T0, T1);
        }
    }

    /**
     * @param fieldRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_getstatic(org.jnode.vm.classmgr.VmConstFieldRef)
     */
    public final void visit_getstatic(VmConstFieldRef fieldRef) {
        //TODO: port to orp-style
        vstack.push(eContext);

        fieldRef.resolve(loader);
        final VmStaticField sf = (VmStaticField) fieldRef.getResolvedVmField();
        if (!sf.getDeclaringClass().isInitialized()) {
            writeInitializeClass(fieldRef, S0);
        }

        // Get static field object
        if (!fieldRef.isWide()) {
            helper.writeGetStaticsEntry(curInstrLabel, T0, sf);
            helper.writePUSH(T0);
        } else {
            helper.writeGetStaticsEntry64(curInstrLabel, T0, T1, sf);
            helper.writePUSH64(T0, T1);
        }

        int type = Item.SignatureToType(fieldRef.getSignature().charAt(0));
        vstack.pushStack(type);
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_goto(int)
     */
    public final void visit_goto(int address) {
        os.writeJMP(helper.getInstrLabel(address));
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_i2b()
     */
    public final void visit_i2b() {
        IntItem v = vstack.popInt();
        v.load(eContext);
        final Register r = v.getRegister();
        os.writeMOVSX(r, r, BYTESIZE);
        vstack.pushItem(IntItem.createRegister(r));

        //		helper.writePOP(T0);
        //		os.writeMOVSX(T0, T0, BYTESIZE);
        //		helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_i2c()
     */
    public final void visit_i2c() {
        IntItem v = vstack.popInt();
        v.load(eContext);
        final Register r = v.getRegister();
        os.writeMOVZX(r, r, BYTESIZE);
        vstack.pushItem(IntItem.createRegister(r));

        //		helper.writePOP(T0);
        //		os.writeMOVZX(T0, T0, WORDSIZE);
        //		helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_i2d()
     */
    public final void visit_i2d() {
        //TODO: port to orp-style
        vstack.push(eContext);
        IntItem v = vstack.popInt();
        v.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        os.writeFILD32(SP, 0);
        os.writeLEA(SP, SP, -4);
        os.writeFSTP64(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_i2f()
     */
    public final void visit_i2f() {
        //TODO: port to orp-style
        vstack.push(eContext);
        IntItem v = vstack.popInt();
        v.release(eContext);
        vstack.pushItem(FloatItem.createStack());

        os.writeFILD32(SP, 0);
        os.writeFSTP32(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_i2l()
     */
    public final void visit_i2l() {
        //TODO: port to orp-style
        vstack.push(eContext);
        IntItem v = vstack.popInt();
        v.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP(Register.EAX);
        os.writeCDQ(); /* Sign extend EAX -> EDX:EAX */
        helper.writePUSH64(Register.EAX, Register.EDX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_i2s()
     */
    public final void visit_i2s() {
        IntItem v = vstack.popInt();
        v.load(eContext);
        final Register r = v.getRegister();
        os.writeMOVSX(r, r, WORDSIZE);
        vstack.pushItem(IntItem.createRegister(r));

        //		helper.writePOP(T0);
        //		os.writeMOVSX(T0, T0, WORDSIZE);
        //		helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_iadd()
     */
    public final void visit_iadd() {
        //REFACTOR: parametrize the os.write operations to avoid code
        // duplication
        //IMPROVE: allow parameter permutation
        IntItem v2 = vstack.popInt();
        IntItem v1 = vstack.popInt();
        PrepareForOperation(v1, v2);
        switch (v2.getKind()) {
        case Item.REGISTER:
            os.writeADD(v1.getRegister(), v2.getRegister());
            break;
        case Item.LOCAL:
            os.writeADD(v1.getRegister(), v2.getOffsetToFP(), FP);
            break;
        case Item.CONSTANT:
            //IMPROVE: implement ADD(reg, imm32)
            // os.writeADD(v1.getRegister(),
            // ((IntConstant)v2.getConstant()).getValue());
            v2.load(eContext);
            os.writeADD(v1.getRegister(), v2.getRegister());
            break;
        }
        v2.release(eContext);
        vstack.pushItem(v1);
        //		helper.writePOP(T0); // value2
        //		helper.writePOP(S0); // value 1
        //		os.writeADD(S0, T0);
        //		helper.writePUSH(S0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_iaload()
     */
    public final void visit_iaload() {
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        Register refReg = ref.getRegister();
        checkBounds(ref, idx);
        if (idx.getKind() == Item.CONSTANT) {
            final int offset = idx.getValue();
            os.writeMOV(INTSIZE, refReg, refReg, offset + VmArray.DATA_OFFSET
                    * 4);
        } else {
            os.writeMOV(INTSIZE, refReg, refReg, idx.getRegister(), 4,
                    VmArray.DATA_OFFSET * 4);
            idx.release(eContext);
        }
        // do not release ref, it is recycled into the result
        vstack.pushItem(IntItem.createRegister(refReg));

        //		helper.writePOP(S0); // Index
        //		helper.writePOP(T0); // Arrayref
        //		checkBounds(T0, S0);
        //		//os.writePUSH(T0, S0, 4, VmArray.DATA_OFFSET * 4);
        //		os.writeMOV(INTSIZE, T0, T0, S0, 4, VmArray.DATA_OFFSET * 4);
        //		helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_iand()
     */
    public final void visit_iand() {
        //REFACTOR: parametrize the os.write operations to avoid code
        // duplication
        //IMPROVE: allow parameter permutation
        IntItem v2 = vstack.popInt();
        IntItem v1 = vstack.popInt();
        PrepareForOperation(v1, v2);
        switch (v2.getKind()) {
        case Item.REGISTER:
            os.writeAND(v1.getRegister(), v2.getRegister());
            break;
        case Item.LOCAL:
            os.writeAND(v1.getRegister(), v2.getOffsetToFP(), FP);
            break;
        case Item.CONSTANT:
            os.writeAND(v1.getRegister(), v2.getValue());
            break;
        }
        v2.release(eContext);
        vstack.pushItem(v1);
        //		helper.writePOP(T0); // value 2
        //		helper.writePOP(S0); // value 1
        //		os.writeAND(S0, T0);
        //		helper.writePUSH(S0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_iastore()
     */
    public final void visit_iastore() {
        IntItem val = vstack.popInt();
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();

        val.loadIf(eContext, ~Item.CONSTANT);
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        Register r = ref.getRegister();

        checkBounds(ref, idx);
        if (idx.getKind() == Item.CONSTANT) {
            int i = idx.getValue();
            if (val.getKind() == Item.CONSTANT) {
                int vc = val.getValue();
                os.writeMOV_Const(r, i + VmArray.DATA_OFFSET * 4, vc);
            } else {
                os.writeMOV(INTSIZE, r, i + VmArray.DATA_OFFSET * 4, val
                        .getRegister());
            }
        } else {
            val.load(eContext); //tmp
            if (val.getKind() == Item.CONSTANT) {
                // int vc = val.getValue();
                //TODO: implement writeMOV_Const disp[reg][idx], imm32
                //os.writeMOV_Const(r, idx.getRegister(), 4,
                // VmArray.DATA_OFFSET * 4, vc);
            } else {
                os.writeMOV(INTSIZE, r, idx.getRegister(), 4,
                        VmArray.DATA_OFFSET * 4, val.getRegister());
            }
        }
        val.release(eContext);
        idx.release(eContext);
        ref.release(eContext);

        //		helper.writePOP(T1); // Value
        //		helper.writePOP(S0); // Index
        //		helper.writePOP(T0); // Arrayref
        //		checkBounds(T0, S0);
        //		os.writeMOV(INTSIZE, T0, S0, 4, VmArray.DATA_OFFSET * 4, T1);
    }

    /**
     * @param value
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_iconst(int)
     */
    public final void visit_iconst(int value) {
        vstack.pushItem(IntItem.createConst(value));
        //		helper.writePUSH(value);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_idiv()
     */
    public final void visit_idiv() {
        //TODO: port to orp-style
        vstack.push(eContext);
        IntItem v2 = vstack.popInt();
        IntItem v1 = vstack.popInt();
        v1.release(eContext);
        v2.release(eContext);
        vstack.pushItem(IntItem.createStack());

        helper.writePOP(Register.ECX); // Value2
        helper.writePOP(Register.EAX); // Value1
        os.writeCDQ();
        os.writeIDIV_EAX(Register.ECX); // EAX = EDX:EAX / ECX
        helper.writePUSH(Register.EAX);
    }

    /**
     * Helper method for visit_if_icmpxx and visit_if_acmpxx
     * 
     * @param address
     * @param jccOpcode
     */
    private final void visit_if_xcmp(int address, int jccOpcode) {
        IntItem v2 = vstack.popInt();
        IntItem v1 = vstack.popInt();
        PrepareForOperation(v1, v2);

        Register r1 = v1.getRegister();

        switch (v2.getKind()) {
        case Item.REGISTER:
            os.writeCMP(r1, v2.getRegister());
            break;
        case Item.LOCAL:
            os.writeCMP(r1, v2.getOffsetToFP(), FP);
            break;
        case Item.CONSTANT:
            int c2 = v2.getValue();
            os.writeCMP(r1, c2);
            break;
        }
        v2.release(eContext);
        vstack.pushItem(v1);
        os.writeJCC(helper.getInstrLabel(address), jccOpcode);

        //		helper.writePOP(S0); // Value2
        //		helper.writePOP(T0); // Value1
        //		os.writeCMP(T0, S0);
        //		os.writeJCC(helper.getInstrLabel(address), jccOpcode);
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_if_acmpeq(int)
     */
    public final void visit_if_acmpeq(int address) {
        visit_if_xcmp(address, X86Constants.JE); // JE
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_if_acmpne(int)
     */
    public final void visit_if_acmpne(int address) {
        visit_if_xcmp(address, X86Constants.JNE); // JNE
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_if_icmpeq(int)
     */
    public final void visit_if_icmpeq(int address) {
        visit_if_xcmp(address, X86Constants.JE); // JE
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_if_icmpge(int)
     */
    public final void visit_if_icmpge(int address) {
        visit_if_xcmp(address, X86Constants.JGE); // JGE
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_if_icmpgt(int)
     */
    public final void visit_if_icmpgt(int address) {
        visit_if_xcmp(address, X86Constants.JG); // JG
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_if_icmple(int)
     */
    public final void visit_if_icmple(int address) {
        visit_if_xcmp(address, X86Constants.JLE); // JLE
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_if_icmplt(int)
     */
    public final void visit_if_icmplt(int address) {
        visit_if_xcmp(address, X86Constants.JL); // JL
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_if_icmpne(int)
     */
    public final void visit_if_icmpne(int address) {
        visit_if_xcmp(address, X86Constants.JNE); // JNE
    }

    private void visit_ifxx(int address, int jccOpcode) {
        //IMPROVE: Constant case / Local case
        RefItem v = vstack.popRef();
        v.load(eContext);
        Register r = v.getRegister();
        os.writeTEST(r, r);
        os.writeJCC(helper.getInstrLabel(address), jccOpcode);
        v.release(eContext);

        //		helper.writePOP(EAX); // Value
        //		//os.writeCMP_EAX(0);
        //		os.writeTEST(EAX, EAX);
        //		os.writeJCC(helper.getInstrLabel(address), jccOpcode);
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ifeq(int)
     */
    public final void visit_ifeq(int address) {
        visit_ifxx(address, X86Constants.JE); // JE
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ifge(int)
     */
    public final void visit_ifge(int address) {
        visit_ifxx(address, X86Constants.JGE); // JGE
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ifgt(int)
     */
    public final void visit_ifgt(int address) {
        visit_ifxx(address, X86Constants.JG); // JG
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ifle(int)
     */
    public final void visit_ifle(int address) {
        visit_ifxx(address, X86Constants.JLE); // JLE
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_iflt(int)
     */
    public final void visit_iflt(int address) {
        visit_ifxx(address, X86Constants.JL); // JL
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ifne(int)
     */
    public final void visit_ifne(int address) {
        visit_ifxx(address, X86Constants.JNE); // JNE
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ifnonnull(int)
     */
    public final void visit_ifnonnull(int address) {
        visit_ifxx(address, X86Constants.JNE);
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ifnull(int)
     */
    public final void visit_ifnull(int address) {
        visit_ifxx(address, X86Constants.JE);
    }

    /**
     * @param index
     * @param incValue
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_iinc(int, int)
     */
    public final void visit_iinc(int index, int incValue) {
        vstack.loadLocal(eContext, index); // avoid aliasing throubles

        final int ebpOfs = stackFrame.getEbpOffset(index);
        //IMPROVE: use INC or ADD reg, 1
        os.writeMOV_Const(T0, incValue);
        os.writeADD(FP, ebpOfs, T0);
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_iload(int)
     */
    public final void visit_iload(int index) {
        vstack.pushItem(IntItem.createLocal(stackFrame.getEbpOffset(index)));
        //		final int ebpOfs = stackFrame.getEbpOffset(index);
        //		os.writeMOV(INTSIZE, T0, FP, ebpOfs);
        //		helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_imul()
     */
    public final void visit_imul() {
        //TODO: port to orp-style
        vstack.push(eContext);
        IntItem v2 = vstack.popInt();
        IntItem v1 = vstack.popInt();
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(IntItem.createStack());

        helper.writePOP(S0); // Value2
        helper.writePOP(EAX); // Value1
        os.writeIMUL_EAX(S0); // EDX:EAX = EAX*S0
        helper.writePUSH(EAX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ineg()
     */
    public final void visit_ineg() {
        IntItem v = vstack.popInt();
        v.load(eContext);
        os.writeNEG(v.getRegister());
        vstack.pushItem(v);

        //		helper.writePOP(T0);
        //		os.writeNEG(T0);
        //		helper.writePUSH(T0);
    }

    /**
     * @param classRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_instanceof(org.jnode.vm.classmgr.VmConstClass)
     */
    public final void visit_instanceof(VmConstClass classRef) {
        //TODO: port to orp-style
        vstack.push(eContext);
        RefItem v = vstack.popRef();
        vstack.pushItem(v);

        /* Objectref is already on the stack */
        writeResolveAndLoadClassToEAX(classRef, S0);

        final Label trueLabel = new Label(this.curInstrLabel + "io-true");
        final Label endLabel = new Label(this.curInstrLabel + "io-end");

        /* Pop objectref */
        helper.writePOP(Register.ECX);
        /* Is instanceof? */
        instanceOf(trueLabel);
        /* Not instanceof */
        os.writeXOR(T0, T0);
        os.writeJMP(endLabel);

        os.setObjectRef(trueLabel);
        os.writeMOV_Const(T0, 1);

        os.setObjectRef(endLabel);
        os.writePUSH(T0);
    }

    private void pushReturnValue(String signature) {
        char t = signature.charAt(signature.length() - 1);
        if (t != 'V') {
            int type = Item.SignatureToType(t);
            vstack.pushStack(type);
        }
    }

    /**
     * @param methodRef
     * @param count
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_invokeinterface(VmConstIMethodRef,
     *      int)
     */
    public final void visit_invokeinterface(VmConstIMethodRef methodRef,
            int count) {
        //TODO: port to orp-style
        vstack.push(eContext);

        methodRef.resolve(loader);
        /*
         * if (!methodRef.getConstClass().isResolved()) { Label startClassLabel =
         * new Label(this.curInstrLabel + "startClass");
         * os.setObjectRef(startClassLabel);
         * resolveClass(methodRef.getConstClass()); patch_NOP(startClassLabel); }
         * 
         * if (!methodRef.isResolved()) { Label startLabel = new
         * Label(this.curInstrLabel + "start"); os.setObjectRef(startLabel);
         * resolveMethod(methodRef); patch_NOP(startLabel);
         */

        final VmMethod method = methodRef.getResolvedVmMethod();
        final int selector = method.getSelector();
        final int imtIndex = selector % ObjectLayout.IMT_LENGTH;
        final int argSlotCount = count - 1;
        final Label noCollLabel = new Label(this.curInstrLabel + "NoCollision");
        final Label findSelectorLabel = new Label(this.curInstrLabel
                + "FindSelector");
        final Label endLabel = new Label(this.curInstrLabel + "End");

        // remove parameters from vstack
        final int nofArgs = method.getNoArguments();
        for (int i = 0; i < nofArgs; i++) {
            //TODO: check parameter type
            Item v = vstack.popItem();
            v.release(eContext);
        }
        //TODO: guess: self is part of the parameters

        // Get objectref -> EBX
        os.writeMOV(INTSIZE, Register.EBX, SP, argSlotCount * slotSize);

        /*
         * // methodRef -> EDX os.writeMOV_Const(Register.EDX, methodRef); //
         * methodRef.selector -> ecx os.writeMOV(INTSIZE, Register.ECX,
         * Register.EDX,
         * context.getVmConstIMethodRefSelectorField().getOffset()); //
         * methodRef.selector -> eax os.writeMOV(INTSIZE, Register.EAX,
         * Register.ECX); // Clear edx os.writeXOR(Register.EDX, Register.EDX); //
         * IMT_LENGTH -> ESI os.writeMOV_Const(Register.ESI,
         * ObjectLayout.IMT_LENGTH); // selector % IMT_LENGTH -> edx
         */
        os.writeMOV_Const(ECX, selector);
        os.writeMOV_Const(EDX, imtIndex);
        // Output: EBX=objectref, ECX=selector, EDX=imtIndex

        /* objectref.TIB -> ebx */
        os.writeMOV(INTSIZE, Register.EBX, Register.EBX, ObjectLayout.TIB_SLOT
                * slotSize);
        /* boolean[] imtCollisions -> esi */
        os.writeMOV(INTSIZE, Register.ESI, Register.EBX,
                (VmArray.DATA_OFFSET + TIBLayout.IMTCOLLISIONS_INDEX)
                        * slotSize);
        /* Has collision at imt[index] ? */
        os.writeMOV(INTSIZE, Register.EAX, Register.ESI, Register.EDX, 1,
                VmArray.DATA_OFFSET * slotSize);
        os.writeTEST_AL(0xFF);
        /* Object[] imt -> esi */
        os.writeMOV(INTSIZE, Register.ESI, Register.EBX,
                (VmArray.DATA_OFFSET + TIBLayout.IMT_INDEX) * slotSize);
        /* selector -> ebx */
        os.writeMOV(INTSIZE, Register.EBX, Register.ECX);

        os.writeJCC(noCollLabel, X86Constants.JZ);

        // We have a collision
        /* imt[index] (=collisionList) -> esi */
        os.writeMOV(INTSIZE, Register.ESI, Register.ESI, Register.EDX, 4,
                VmArray.DATA_OFFSET * slotSize);
        /* collisionList.length -> ecx */
        os.writeMOV(INTSIZE, Register.ECX, Register.ESI, VmArray.LENGTH_OFFSET
                * slotSize);
        /* &collisionList[0] -> esi */
        os.writeLEA(Register.ESI, Register.ESI, VmArray.DATA_OFFSET * slotSize);

        os.setObjectRef(findSelectorLabel);

        /* collisionList[index] -> eax */
        os.writeLODSD();
        /* collisionList[index].selector == selector? */
        os.writeMOV(INTSIZE, Register.EDX, Register.EAX, context
                .getVmMethodSelectorField().getOffset());
        os.writeCMP(Register.EBX, Register.EDX);
        os.writeJCC(endLabel, X86Constants.JE);
        try {
            os.writeLOOP(findSelectorLabel);
        } catch (UnresolvedObjectRefException ex) {
            throw new CompileError(ex);
        }
        /* Force a NPE further on */
        os.writeXOR(Register.EAX, Register.EAX);
        os.writeJMP(endLabel);

        os.setObjectRef(noCollLabel);
        /* imt[index] -> eax */
        os.writeMOV(INTSIZE, Register.EAX, Register.ESI, Register.EDX, 4,
                VmArray.DATA_OFFSET * slotSize);

        os.setObjectRef(endLabel);

        /** Now invoke the method */
        helper.invokeJavaMethod(methodRef.getSignature());

        pushReturnValue(methodRef.getSignature());
    }

    /**
     * @param methodRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_invokespecial(org.jnode.vm.classmgr.VmConstMethodRef)
     */
    public final void visit_invokespecial(VmConstMethodRef methodRef) {
        //TODO: port to orp-style
        vstack.push(eContext);

        methodRef.resolve(loader);
        try {
            final VmMethod sm = methodRef.getResolvedVmMethod();

            final int nofArgs = sm.getNoArguments();
            for (int i = 0; i < nofArgs; i++) {
                //TODO: check parameter type
                Item v = vstack.popItem();
                v.release(eContext);
            }

            // Get method from statics table
            helper.writeGetStaticsEntry(curInstrLabel, EAX, sm);
            helper.invokeJavaMethod(methodRef.getSignature());
        } catch (ClassCastException ex) {
            System.out.println(methodRef.getResolvedVmMethod().getClass()
                    .getName()
                    + "#" + methodRef.getName());
            throw ex;
        }

        pushReturnValue(methodRef.getSignature());

        /*
         * methodRef.resolve(loader);
         * writeResolveAndLoadClassToEAX(methodRef.getConstClass(), S0);
         * writeResolveAndLoadMethodToEAX(methodRef, S0);
         * helper.invokeJavaMethod(methodRef.getSignature(), context);
         */
    }

    /**
     * @param methodRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_invokestatic(org.jnode.vm.classmgr.VmConstMethodRef)
     */
    public final void visit_invokestatic(VmConstMethodRef methodRef) {
        //TODO: port to orp-style
        vstack.push(eContext);

        methodRef.resolve(loader);
        final VmStaticMethod sm = (VmStaticMethod) methodRef
                .getResolvedVmMethod();

        final int nofArgs = sm.getNoArguments();
        for (int i = 0; i < nofArgs; i++) {
            //TODO: check parameter type
            Item v = vstack.popItem();
            v.release(eContext);
        }

        // Get static field object
        helper.writeGetStaticsEntry(curInstrLabel, EAX, sm);
        helper.invokeJavaMethod(methodRef.getSignature());

        pushReturnValue(methodRef.getSignature());
    }

    /**
     * @param methodRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_invokevirtual(org.jnode.vm.classmgr.VmConstMethodRef)
     */
    public final void visit_invokevirtual(VmConstMethodRef methodRef) {
        //TODO: port to orp-style
        vstack.push(eContext);

        methodRef.resolve(loader);
        final VmMethod mts = methodRef.getResolvedVmMethod();

        final int nofArgs = mts.getNoArguments();
        for (int i = 0; i < nofArgs; i++) {
            //TODO: check parameter type
            Item v = vstack.popItem();
            v.release(eContext);
        }

        if (mts.isStatic()) { throw new IncompatibleClassChangeError(
                "Static method in invokevirtual"); }
        final VmInstanceMethod method = (VmInstanceMethod) mts;
        final int tibOffset = method.getTibOffset();
        final int argSlotCount = Signature.getArgSlotCount(methodRef
                .getSignature());

        /* Get objectref -> S0 */
        os.writeMOV(INTSIZE, S0, SP, argSlotCount * slotSize);
        /* Get VMT of objectef -> S0 */
        os.writeMOV(INTSIZE, S0, S0, ObjectLayout.TIB_SLOT * slotSize);
        /* Get entry in VMT -> EAX */
        os.writeMOV(INTSIZE, EAX, S0, (VmArray.DATA_OFFSET + tibOffset)
                * slotSize);
        /* Now invoke the method */
        helper.invokeJavaMethod(methodRef.getSignature());

        pushReturnValue(methodRef.getSignature());
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ior()
     */
    public final void visit_ior() {
        //REFACTOR: parametrize the os.write operations to avoid code
        // duplication
        //IMPROVE: allow parameter permutation
        IntItem v2 = vstack.popInt();
        IntItem v1 = vstack.popInt();
        PrepareForOperation(v1, v2);
        switch (v2.getKind()) {
        case Item.REGISTER:
            os.writeOR(v1.getRegister(), v2.getRegister());
            break;
        case Item.LOCAL:
            os.writeOR(v1.getRegister(), v2.getOffsetToFP(), FP);
            break;
        case Item.CONSTANT:
            //IMPROVE: implement OR(reg, imm32)
            //os.writeOR(v1.getRegister(),
            // ((IntConstant)v2.getConstant()).getValue());
            v2.load(eContext);
            os.writeOR(v1.getRegister(), v2.getRegister());
            break;
        }
        v2.release(eContext);
        vstack.pushItem(v1);
        //		helper.writePOP(T0); // value2
        //		helper.writePOP(S0); // value 1
        //		os.writeOR(S0, T0);
        //		helper.writePUSH(S0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_irem()
     */
    public final void visit_irem() {
        //TODO: port to orp-style
        vstack.push(eContext);
        IntItem v2 = vstack.popInt();
        IntItem v1 = vstack.popInt();
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(IntItem.createStack());

        helper.writePOP(S0); // Value2
        helper.writePOP(EAX); // Value1
        os.writeCDQ();
        os.writeIDIV_EAX(S0); // EAX = EDX:EAX / S0
        helper.writePUSH(EDX); // Remainder
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ireturn()
     */
    public final void visit_ireturn() {
        vstack.requestRegister(eContext, EAX);
        IntItem v = vstack.popInt();
        v.loadTo(eContext, EAX);

        //		helper.writePOP(T0);
        visit_return();
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ishl()
     */
    public final void visit_ishl() {
        vstack.requestRegister(eContext, ECX);
        IntItem shift = vstack.popInt();
        IntItem value = vstack.popInt();

        shift.loadToIf(eContext, ~Item.CONSTANT, ECX);
        value.load(eContext);

        Register v = value.getRegister();

        if (shift.getKind() == Item.CONSTANT) {
            int offset = shift.getValue();
            os.writeSAL(v, offset);
        } else {
            os.writeSAL_CL(v);
        }
        vstack.pushItem(value);

        //		helper.writePOP(ECX);
        //		helper.writePOP(EAX);
        //		os.writeSAL_CL(EAX);
        //		helper.writePUSH(EAX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ishr()
     */
    public final void visit_ishr() {
        vstack.requestRegister(eContext, ECX);
        IntItem shift = vstack.popInt();
        IntItem value = vstack.popInt();

        shift.loadToIf(eContext, ~Item.CONSTANT, ECX);
        value.load(eContext);

        Register v = value.getRegister();

        if (shift.getKind() == Item.CONSTANT) {
            int offset = shift.getValue();
            os.writeSAR(v, offset);
        } else {
            os.writeSAR_CL(v);
        }
        vstack.pushItem(value);

        //		helper.writePOP(ECX);
        //		helper.writePOP(EAX);
        //		os.writeSAR_CL(EAX);
        //		helper.writePUSH(EAX);
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_istore(int)
     */
    public final void visit_istore(int index) {
        int disp = stackFrame.getEbpOffset(index);
        IntItem i = vstack.popInt();
        i.loadIf(eContext, ~Item.CONSTANT);

        if (i.getKind() == Item.CONSTANT) {
            //TODO: check whether constant is different from NULL (loaded with
            // ldc)
            os.writeMOV_Const(FP, disp, 0);
        } else {
            os.writeMOV(INTSIZE, FP, disp, i.getRegister());
            i.release(eContext);
        }

        //		int ebpOfs = stackFrame.getEbpOffset(index);
        //		helper.writePOP(FP, ebpOfs);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_isub()
     */
    public final void visit_isub() {
        //REFACTOR: parametrize the os.write operations to avoid code
        // duplication
        IntItem v2 = vstack.popInt();
        IntItem v1 = vstack.popInt();
        PrepareForOperation(v1, v2);
        switch (v2.getKind()) {
        case Item.REGISTER:
            os.writeSUB(v1.getRegister(), v2.getRegister());
            break;
        case Item.LOCAL:
            os.writeSUB(v1.getRegister(), v2.getOffsetToFP(), FP);
            break;
        case Item.CONSTANT:
            //IMPROVE: implement SUB(reg, imm32)
            //os.writeSUB(v1.getRegister(),
            // ((IntConstant)v2.getConstant()).getValue());
            v2.load(eContext);
            os.writeSUB(v1.getRegister(), v2.getRegister());
            break;
        }
        v2.release(eContext);
        vstack.pushItem(v1);
        //		helper.writePOP(T0);
        //		helper.writePOP(S0);
        //		os.writeSUB(S0, T0);
        //		helper.writePUSH(S0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_iushr()
     */
    public final void visit_iushr() {
        vstack.requestRegister(eContext, ECX);
        IntItem shift = vstack.popInt();
        IntItem value = vstack.popInt();

        shift.loadToIf(eContext, ~Item.CONSTANT, ECX);
        value.load(eContext);

        Register v = value.getRegister();

        if (shift.getKind() == Item.CONSTANT) {
            int offset = shift.getValue();
            os.writeSHR(v, offset);
        } else {
            os.writeSHR_CL(v);
        }
        vstack.pushItem(value);

        //		helper.writePOP(Register.ECX);
        //		helper.writePOP(Register.EAX);
        //		os.writeSHR_CL(Register.EAX);
        //		helper.writePUSH(Register.EAX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ixor()
     */
    public final void visit_ixor() {
        //REFACTOR: parametrize the os.write operations to avoid code
        // duplication
        //IMPROVE: allow parameter permutation
        IntItem v2 = vstack.popInt();
        IntItem v1 = vstack.popInt();
        PrepareForOperation(v1, v2);
        switch (v2.getKind()) {
        case Item.REGISTER:
            os.writeXOR(v1.getRegister(), v2.getRegister());
            break;
        case Item.LOCAL:
            os.writeXOR(v1.getRegister(), v2.getOffsetToFP(), FP);
            break;
        case Item.CONSTANT:
            //IMPROVE: implement XOR(reg, imm32)
            // os.writeXOR(v1.getRegister(),
            // ((IntConstant)v2.getConstant()).getValue());
            v2.load(eContext);
            os.writeXOR(v1.getRegister(), v2.getRegister());
            break;
        }
        v2.release(eContext);
        vstack.pushItem(v1);
        //		helper.writePOP(T0);
        //		helper.writePOP(S0);
        //		os.writeXOR(S0, T0);
        //		helper.writePUSH(S0);
    }

    /**
     * @param address
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_jsr(int)
     */
    public final void visit_jsr(int address) {
        os.writeCALL(helper.getInstrLabel(address));
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_l2d()
     */
    public final void visit_l2d() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v = vstack.popItem(Item.LONG);
        v.release(eContext);
        vstack.pushItem(DoubleItem.createStack());

        os.writeFILD64(SP, 0);
        os.writeFSTP64(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_l2f()
     */
    public final void visit_l2f() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v = vstack.popItem(Item.LONG);
        v.release(eContext);
        vstack.pushItem(FloatItem.createStack());

        os.writeFILD64(SP, 0);
        os.writeLEA(SP, SP, 4);
        os.writeFSTP32(SP, 0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_l2i()
     */
    public final void visit_l2i() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v = vstack.popItem(Item.LONG);
        v.release(eContext);
        vstack.pushItem(IntItem.createStack());

        helper.writePOP64(T0, T1);
        helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ladd()
     */
    public final void visit_ladd() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP64(T0, T1); // Value 2
        helper.writePOP64(S0, S1); // Value 1
        os.writeADD(S0, T0);
        os.writeADC(S1, T1);
        helper.writePUSH64(S0, S1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_laload()
     */
    public final void visit_laload() {
        //TODO: port to orp-style
        vstack.push(eContext);
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        idx.release(eContext);
        ref.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP(T0); // Index
        helper.writePOP(S0); // Arrayref
        checkBounds(S0, T0);
        os.writeLEA(S0, S0, T0, 8, VmArray.DATA_OFFSET * 4);
        os.writeMOV(INTSIZE, T0, S0, 0);
        os.writeMOV(INTSIZE, T1, S0, 4);
        helper.writePUSH64(T0, T1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_land()
     */
    public final void visit_land() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP64(T0, T1); // Value 2
        helper.writePOP64(S0, S1); // Value 1
        os.writeAND(S0, T0);
        os.writeAND(S1, T1);
        helper.writePUSH64(S0, S1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lastore()
     */
    public final void visit_lastore() {
        //TODO: port to orp-style
        vstack.push(eContext);
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        idx.release(eContext);
        ref.release(eContext);

        os.writeMOV(INTSIZE, T0, SP, 8); // Index
        os.writeMOV(INTSIZE, S0, SP, 12); // Arrayref
        checkBounds(S0, T0);
        os.writeLEA(S0, S0, T0, 8, VmArray.DATA_OFFSET * 4);
        helper.writePOP64(T0, T1); // Value
        os.writeMOV(INTSIZE, S0, 0, T0);
        os.writeMOV(INTSIZE, S0, 4, T1);
        os.writeLEA(SP, SP, 8); // Remove index, arrayref
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lcmp()
     */
    public final void visit_lcmp() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(IntItem.createStack());

        helper.writePOP64(Register.EBX, Register.ECX); // Value 2
        helper.writePOP64(Register.EAX, Register.EDX); // Value 1

        Label ltLabel = new Label(curInstrLabel + "lt");
        Label endLabel = new Label(curInstrLabel + "end");

        os.writeXOR(Register.ESI, Register.ESI);
        os.writeSUB(Register.EAX, Register.EBX);
        os.writeSBB(Register.EDX, Register.ECX);
        os.writeJCC(ltLabel, X86Constants.JL); // JL
        os.writeOR(Register.EAX, Register.EDX);
        os.writeJCC(endLabel, X86Constants.JZ); // value1 == value2
        /** GT */
        os.writeINC(Register.ESI);
        os.writeJMP(endLabel);
        /** LT */
        os.setObjectRef(ltLabel);
        os.writeDEC(Register.ESI);
        os.setObjectRef(endLabel);
        helper.writePUSH(Register.ESI);
    }

    /**
     * @param v
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lconst(long)
     */
    public final void visit_lconst(long v) {
        vstack.pushItem(LongItem.createConst(v));
        //		final int lsb = (int) (v & 0xFFFFFFFFL);
        //		final int msb = (int) ((v >>> 32) & 0xFFFFFFFFL);
        //		//log.debug("lconst v=" + NumberUtils.hex(v,16) + ",lsb=" +
        // NumberUtils.hex(lsb, 8) +
        //		// ",msb=" + NumberUtils.hex(msb, 8));
        //
        //		os.writeMOV_Const(T0, lsb);
        //		os.writeMOV_Const(T1, msb);
        //		helper.writePUSH64(T0, T1);
    }

    /**
     * @param value
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ldc(VmConstString)
     */
    public final void visit_ldc(VmConstString value) {
        //REFACTOR: use object constant
        vstack.push(eContext);
        vstack.pushItem(RefItem.createStack());
        helper.writeGetStaticsEntry(curInstrLabel, T0, value);
        helper.writePUSH(T0);

        //		//REFACTOR: use Constant instead of Object!
        //		if (value instanceof Integer) {
        //			visit_iconst(((Integer) value).intValue());
        //// os.writeMOV_Const(Register.EAX, ((Integer) value).intValue());
        //		} else if (value instanceof Float) {
        //			visit_fconst(((Float) value).floatValue());
        //// os.writeMOV_Const(Register.EAX, Float.floatToRawIntBits(((Float)
        // value).floatValue()));
        //		} else if (value instanceof String) {
        //			//REFACTOR: useCreateStackstant
        //			vstack.pushItem(RefItem.createStack());
        //			helper.writeGetStaticsEntry(curInstrLabel, T0, value);
        //			helper.writePUSH(T0);
        //		} else {
        //			throw new ClassFormatError("ldc with unknown type " +
        // value.getClass().getName());
        //		}
        //// helper.writePUSH(Register.EAX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ldiv()
     */
    public final void visit_ldiv() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.invokeJavaMethod(context.getLdivMethod());
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lload(int)
     */
    public final void visit_lload(int index) {
        vstack.pushItem(LongItem.createLocal(stackFrame.getEbpOffset(index)));
        //		int ebpOfs = stackFrame.getWideEbpOffset(index);
        //		os.writeMOV(INTSIZE, T0, FP, ebpOfs);
        //		os.writeMOV(INTSIZE, T1, FP, ebpOfs + 4);
        //		helper.writePUSH64(T0, T1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lmul()
     */
    public final void visit_lmul() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP64(Register.EBX, Register.ECX); // Value 2
        helper.writePOP64(Register.ESI, Register.EDI); // Value 1

        Label tmp1 = new Label(curInstrLabel + "$tmp1");
        Label tmp2 = new Label(curInstrLabel + "$tmp2");

        os.writeMOV(INTSIZE, Register.EAX, Register.EDI); // hi2
        os.writeOR(Register.EAX, Register.ECX); // hi1 | hi2
        os.writeJCC(tmp1, X86Constants.JNZ);
        os.writeMOV(INTSIZE, Register.EAX, Register.ESI); // lo2
        os.writeMUL_EAX(Register.EBX); // lo1*lo2
        os.writeJMP(tmp2);
        os.setObjectRef(tmp1);
        os.writeMOV(INTSIZE, Register.EAX, Register.ESI); // lo2
        os.writeMUL_EAX(Register.ECX); // hi1*lo2
        os.writeMOV(INTSIZE, Register.ECX, Register.EAX);
        os.writeMOV(INTSIZE, Register.EAX, Register.EDI); // hi2
        os.writeMUL_EAX(Register.EBX); // hi2*lo1
        os.writeADD(Register.ECX, Register.EAX); // hi2*lo1 + hi1*lo2
        os.writeMOV(INTSIZE, Register.EAX, Register.ESI); // lo2
        os.writeMUL_EAX(Register.EBX); // lo1*lo2
        os.writeADD(Register.EDX, Register.ECX); // hi2*lo1 + hi1*lo2 +
                                                 // hi(lo1*lo2)
        os.setObjectRef(tmp2);
        // Reload the statics table, since it was destroyed here
        helper.writeLoadSTATICS(curInstrLabel, "lmul", false);
        helper.writePUSH64(Register.EAX, Register.EDX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lneg()
     */
    public final void visit_lneg() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v = vstack.popItem(Item.LONG);
        vstack.pushItem(v);

        helper.writePOP64(T0, T1);
        os.writeNEG(T1); // msb := -msb
        os.writeNEG(T0); // lsb := -lsb
        os.writeSBB(T1, 0); // high += borrow
        helper.writePUSH64(T0, T1);
        /*
         * os.writeNEG(SP, 0); os.writeNEG(SP, 4);
         */
    }

    /**
     * @param defAddress
     * @param matchValues
     * @param addresses
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lookupswitch(int,
     *      int[], int[])
     */
    public final void visit_lookupswitch(int defAddress, int[] matchValues,
            int[] addresses) {
        final int n = matchValues.length;
        //BootLog.debug("lookupswitch length=" + n);

        IntItem key = vstack.popInt();
        Register r = key.getRegister();
        // Conservative assumption, flush stack
        vstack.push(eContext);
        key.release(eContext);

        for (int i = 0; i < n; i++) {
            os.writeCMP(r, matchValues[ i]);
            os.writeJCC(helper.getInstrLabel(addresses[ i]), X86Constants.JE); // JE
        }
        os.writeJMP(helper.getInstrLabel(defAddress));

        //		helper.writePOP(Register.EAX); // Key
        //		for (int i = 0; i < matchValues.length; i++) {
        //			os.writeCMP_EAX(matchValues[i]);
        //			os.writeJCC(helper.getInstrLabel(addresses[i]), X86Constants.JE); //
        // JE
        //		}
        //		os.writeJMP(helper.getInstrLabel(defAddress));
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lor()
     */
    public final void visit_lor() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP64(T0, T1); // Value 2
        helper.writePOP64(S0, S1); // Value 1
        os.writeOR(S0, T0);
        os.writeOR(S1, T1);
        helper.writePUSH64(S0, S1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lrem()
     */
    public final void visit_lrem() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.invokeJavaMethod(context.getLremMethod());
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lreturn()
     */
    public final void visit_lreturn() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v = vstack.popItem(Item.LONG);
        v.release(eContext);

        helper.writePOP64(T0, T1);
        visit_return();
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lshl()
     */
    public final void visit_lshl() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP(Register.ECX); // Value 2
        helper.writePOP64(Register.EAX, Register.EDX); // Value 1
        os.writeAND(Register.ECX, 63);
        os.writeCMP(Register.ECX, 32);
        Label gt32Label = new Label(curInstrLabel + "gt32");
        Label endLabel = new Label(curInstrLabel + "end");
        os.writeJCC(gt32Label, X86Constants.JAE); // JAE
        /** ECX < 32 */
        os.writeSHLD_CL(Register.EDX, Register.EAX);
        os.writeSHL_CL(Register.EAX);
        os.writeJMP(endLabel);
        /** ECX >= 32 */
        os.setObjectRef(gt32Label);
        os.writeMOV(INTSIZE, Register.EDX, Register.EAX);
        os.writeXOR(Register.EAX, Register.EAX);
        os.writeSHL_CL(Register.EDX);
        os.setObjectRef(endLabel);
        helper.writePUSH64(Register.EAX, Register.EDX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lshr()
     */
    public final void visit_lshr() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP(Register.ECX); // Value 2
        helper.writePOP64(Register.EAX, Register.EDX); // Value 1
        os.writeAND(Register.ECX, 63);
        os.writeCMP(Register.ECX, 32);
        Label gt32Label = new Label(curInstrLabel + "gt32");
        Label endLabel = new Label(curInstrLabel + "end");
        os.writeJCC(gt32Label, X86Constants.JAE); // JAE
        /** ECX < 32 */
        os.writeSHRD_CL(Register.EAX, Register.EDX);
        os.writeSAR_CL(Register.EDX);
        os.writeJMP(endLabel);
        /** ECX >= 32 */
        os.setObjectRef(gt32Label);
        os.writeMOV(INTSIZE, Register.EAX, Register.EDX);
        os.writeSAR(Register.EDX, 31);
        os.writeSAR_CL(Register.EAX);
        os.setObjectRef(endLabel);
        helper.writePUSH64(Register.EAX, Register.EDX);
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lstore(int)
     */
    public final void visit_lstore(int index) {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v = vstack.popItem(Item.LONG);
        v.release(eContext);

        int ebpOfs = stackFrame.getWideEbpOffset(index);
        helper.writePOP64(T0, T1);
        os.writeMOV(INTSIZE, FP, ebpOfs, T0);
        os.writeMOV(INTSIZE, FP, ebpOfs + 4, T1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lsub()
     */
    public final void visit_lsub() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP64(T0, T1); // Value 2
        helper.writePOP64(S0, S1); // Value 1
        os.writeSUB(S0, T0);
        os.writeSBB(S1, T1);
        helper.writePUSH64(S0, S1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lushr()
     */
    public final void visit_lushr() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP(Register.ECX); // Value 2
        helper.writePOP64(Register.EAX, Register.EDX); // Value 1
        os.writeAND(Register.ECX, 63);
        os.writeCMP(Register.ECX, 32);
        Label gt32Label = new Label(curInstrLabel + "gt32");
        Label endLabel = new Label(curInstrLabel + "end");
        os.writeJCC(gt32Label, X86Constants.JAE); // JAE
        /** ECX < 32 */
        os.writeSHRD_CL(Register.EAX, Register.EDX);
        os.writeSHR_CL(Register.EDX);
        os.writeJMP(endLabel);
        /** ECX >= 32 */
        os.setObjectRef(gt32Label);
        os.writeMOV(INTSIZE, Register.EAX, Register.EDX);
        os.writeXOR(Register.EDX, Register.EDX);
        os.writeSHR_CL(Register.EAX);
        os.setObjectRef(endLabel);
        helper.writePUSH64(Register.EAX, Register.EDX);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_lxor()
     */
    public final void visit_lxor() {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item v2 = vstack.popItem(Item.LONG);
        Item v1 = vstack.popItem(Item.LONG);
        v2.release(eContext);
        v1.release(eContext);
        vstack.pushItem(LongItem.createStack());

        helper.writePOP64(T0, T1); // Value 2
        helper.writePOP64(S0, S1); // Value 1
        os.writeXOR(S0, T0);
        os.writeXOR(S1, T1);
        helper.writePUSH64(S0, S1);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_monitorenter()
     */
    public final void visit_monitorenter() {
        vstack.push(eContext);
        RefItem v = vstack.popRef();
        v.release(eContext);

        // Objectref is already on the stack
        helper.invokeJavaMethod(context.getMonitorEnterMethod());
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_monitorexit()
     */
    public final void visit_monitorexit() {
        vstack.push(eContext);
        RefItem v = vstack.popRef();
        v.release(eContext);

        // Objectref is already on the stack
        helper.invokeJavaMethod(context.getMonitorExitMethod());
    }

    /**
     * @param clazz
     * @param dimensions
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_multianewarray(VmConstClass,
     *      int)
     */
    public final void visit_multianewarray(VmConstClass clazz, int dimensions) {
        //TODO: port to orp-style
        vstack.push(eContext);

        System.out.println("multianewarray not implemented yet in "
                + helper.getMethod());
        // Dummy to maintain the stack structure, but cause a
        // NullPointerException
        for (int i = 0; i < dimensions; i++) {
            IntItem v = vstack.popInt();
            v.release(eContext);
            helper.writePOP(Register.EAX);
        }
        vstack.pushItem(RefItem.createConst(null));
        helper.writePUSH(0);
    }

    /**
     * @param classRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_new(org.jnode.vm.classmgr.VmConstClass)
     */
    public final void visit_new(VmConstClass classRef) {
        //TODO: port to orp-style
        vstack.push(eContext);

        writeResolveAndLoadClassToEAX(classRef, S0);
        /* Setup a call to SoftByteCodes.allocObject */
        helper.writePUSH(Register.EAX); /* vmClass */
        helper.writePUSH(-1); /* Size */
        helper.invokeJavaMethod(context.getAllocObjectMethod());
        /* Result is already on the stack */
        vstack.pushItem(RefItem.createStack());
    }

    /**
     * @param type
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_newarray(int)
     */
    public final void visit_newarray(int type) {
        IntItem count = vstack.popInt();
        count.loadIf(eContext, Item.STACK);
        //		helper.writePOP(S0); /* Elements */

        /* Setup a call to SoftByteCodes.allocArray */
        helper.writePUSH(type); /* type */
        //		helper.writePUSH(S0); /* elements */
        count.push(eContext);

        helper.invokeJavaMethod(context.getAllocPrimitiveArrayMethod());
        /* Result is already on the stack */

        vstack.pushItem(RefItem.createStack());
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_nop()
     */
    public final void visit_nop() {
        os.writeNOP();
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_pop()
     */
    public final void visit_pop() {
        Item v = vstack.popItem();
        assertCondition(v.getCategory() == 1);
        if (v.getKind() == Item.STACK) {
            os.writeLEA(SP, SP, 4);
        } else {
            v.release(eContext);
        }
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_pop2()
     */
    public final void visit_pop2() {
        Item v = vstack.popItem();
        assertCondition(v.getCategory() == 2);
        if (v.getKind() == Item.STACK) {
            os.writeLEA(SP, SP, 8);
        } else {
            v.release(eContext);
        }
        //		os.writeLEA(SP, SP, 8);
    }

    /**
     * @param fieldRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_putfield(org.jnode.vm.classmgr.VmConstFieldRef)
     */
    public final void visit_putfield(VmConstFieldRef fieldRef) {
        fieldRef.resolve(loader);
        final VmField field = fieldRef.getResolvedVmField();
        if (field.isStatic()) { throw new IncompatibleClassChangeError(
                "getfield called on static field " + fieldRef.getName()); }
        final VmInstanceField inf = (VmInstanceField) field;
        final int offset = inf.getOffset();
        final boolean wide = fieldRef.isWide();

        if (!wide) {
            vstack.push(eContext);
            Item val = vstack.popItem();
            RefItem ref = vstack.popRef();
            val.release(eContext);
            ref.release(eContext);

            // Decide whether to use a big switch on the type of to
            // implement this in the item (putStatic and putField)

            //			val.loadIf(eContext, ~Item.CONSTANT);
            //			ref.load(eContext);
            //			Register r = ref.getRegister();
            //			if (val.getKind() == Item.CONSTANT) {
            //				int c = ((IntConstant)val.getConstant()).getValue();
            //				os.writeMOV_Const(r, offset, c);
            //
            //			} else {
            //				os.writeMOV(INTSIZE, r, offset, val.getRegister());
            //				val.release();
            //			}
            /* Value -> T0 */
            helper.writePOP(T0);
            /* Objectref -> S0 */
            helper.writePOP(S0); // Objectref
            os.writeMOV(INTSIZE, S0, offset, T0);
            helper.writePutfieldWriteBarrier(inf, S0, T0, S1);
            //			ref.release();

        } else {
            //IMPROVE: 64bit operations
            vstack.push(eContext);
            Item val = vstack.popItem();
            RefItem ref = vstack.popRef();
            val.release(eContext);
            ref.release(eContext);

            /* Value LSB -> T0 */
            helper.writePOP(T0);
            /* Value MSB -> T1 */
            helper.writePOP(T1);
            /* Objectref -> S0 */
            helper.writePOP(S0); // Objectref
            /** Msb */
            os.writeMOV(INTSIZE, S0, offset + 4, T1);
            /** Lsb */
            os.writeMOV(INTSIZE, S0, offset, T0);
        }
    }

    /**
     * @param fieldRef
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_putstatic(org.jnode.vm.classmgr.VmConstFieldRef)
     */
    public final void visit_putstatic(VmConstFieldRef fieldRef) {
        //TODO: port to orp-style
        vstack.push(eContext);
        Item val = vstack.popItem();
        val.release(eContext);

        fieldRef.resolve(loader);
        final VmStaticField sf = (VmStaticField) fieldRef.getResolvedVmField();

        if (!sf.getDeclaringClass().isInitialized()) {
            writeInitializeClass(fieldRef, S0);
        }
        // Put static field
        if (!fieldRef.isWide()) {
            helper.writePOP(T0);
            helper.writePutStaticsEntry(curInstrLabel, T0, sf);
            helper.writePutstaticWriteBarrier(sf, T0, S1);
        } else {
            helper.writePOP64(T0, T1);
            helper.writePutStaticsEntry64(curInstrLabel, T0, T1, sf);
        }
    }

    /**
     * @param index
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_ret(int)
     */
    public final void visit_ret(int index) {
        final int ebpOfs = stackFrame.getEbpOffset(index);
        os.writeMOV(INTSIZE, T0, FP, ebpOfs);
        os.writeJMP(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_return()
     */
    public final void visit_return() {
        stackFrame.emitReturn();
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_saload()
     */
    public final void visit_saload() {
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        Register refReg = ref.getRegister();
        checkBounds(ref, idx);
        if (idx.getKind() == Item.CONSTANT) {
            final int offset = idx.getValue();
            os.writeMOV(WORDSIZE, refReg, refReg, offset + VmArray.DATA_OFFSET
                    * 4);
        } else {
            os.writeMOV(WORDSIZE, refReg, refReg, idx.getRegister(), 2,
                    VmArray.DATA_OFFSET * 4);
            idx.release(eContext);
        }
        os.writeMOVSX(refReg, refReg, WORDSIZE);
        // do not release ref, it is recycled into the result
        vstack.pushItem(IntItem.createRegister(refReg));

        //		helper.writePOP(T0); // Index
        //		helper.writePOP(S0); // Arrayref
        //		checkBounds(S0, T0);
        //		os.writeMOV(WORDSIZE, T0, S0, T0, 2, VmArray.DATA_OFFSET * 4);
        //		os.writeMOVSX(T0, T0, WORDSIZE);
        //		helper.writePUSH(T0);
    }

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_sastore()
     */
    public final void visit_sastore() {
        IntItem val = vstack.popInt();
        IntItem idx = vstack.popInt();
        RefItem ref = vstack.popRef();

        //IMPROVE: optimize case with const value
        val.load(eContext);
        idx.loadIf(eContext, ~Item.CONSTANT);
        ref.load(eContext);
        final Register r = ref.getRegister();
        final Register v = val.getRegister();

        checkBounds(ref, idx);
        if (idx.getKind() == Item.CONSTANT) {
            int i = idx.getValue();
            os.writeMOV(WORDSIZE, r, i + VmArray.DATA_OFFSET * 4, v);
        } else {
            os.writeMOV(WORDSIZE, r, idx.getRegister(), 2,
                    VmArray.DATA_OFFSET * 4, v);
        }
        val.release(eContext);
        idx.release(eContext);
        ref.release(eContext);

        //		helper.writePOP(T1); // Value
        //		helper.writePOP(T0); // Index
        //		helper.writePOP(S0); // Arrayref
        //		checkBounds(S0, T0);
        //		os.writeMOV(WORDSIZE, S0, T0, 2, VmArray.DATA_OFFSET * 4, T1);
    }

    //	/**
    //	 * @param value
    //	 * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_sipush(short)
    //	 */
    //	public final void visit_sipush(short value) {
    //		visit_iconst(value);
    //// os.writeMOV_Const(T0, value);
    //// helper.writePUSH(T0);
    //	}

    /**
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_swap()
     */
    public final void visit_swap() {
        Item v1 = vstack.popItem();
        Item v2 = vstack.popItem();
        assertCondition((v1.getCategory() == 1) && (v2.getCategory() == 1));
        final boolean v1IsBool = (v1.getKind() == Item.STACK);
        final boolean v2IsBool = (v2.getKind() == Item.STACK);
        if (v1IsBool && v2IsBool) {
            // both on the stack: must be popped to be inverted (inverting only
            // on vstack not enough)
            v1.load(eContext);
            v2.load(eContext);
        }
        vstack.pushItem(v1);
        vstack.pushItem(v2);

        //		helper.writePOP(T0); // Value1
        //		helper.writePOP(S0); // Value2
        //		helper.writePUSH(T0); // Value1
        //		helper.writePUSH(S0); // Value2
    }

    /**
     * @param defAddress
     * @param lowValue
     * @param highValue
     * @param addresses
     * @see org.jnode.vm.bytecode.BytecodeVisitor#visit_tableswitch(int, int,
     *      int, int[])
     */
    public final void visit_tableswitch(int defAddress, int lowValue,
            int highValue, int[] addresses) {
        //IMPROVE: check Jaos implementation
        IntItem v = vstack.popInt();
        v.load(eContext);
        final Register r = v.getRegister();
        vstack.push(eContext);

        final int n = addresses.length;
        //TODO: port optimized version of L1
        // Space wasting, but simple implementation
        for (int i = 0; i < n; i++) {
            os.writeCMP(r, lowValue + i);
            os.writeJCC(helper.getInstrLabel(addresses[ i]), X86Constants.JE); // JE
        }
        os.writeJMP(helper.getInstrLabel(defAddress));

        v.release(eContext);

        //		helper.writePOP(Register.EAX);
        //		// Space wasting, but simple implementation
        //		for (int i = 0; i < addresses.length; i++) {
        //			os.writeCMP_EAX(lowValue + i);
        //			os.writeJCC(helper.getInstrLabel(addresses[i]), X86Constants.JE); //
        // JE
        //		}
        //		os.writeJMP(helper.getInstrLabel(defAddress));
    }

    /**
     * Emit code to validate an index of a given array
     * 
     * @param ref
     * @param index
     */
    private final void checkBounds(RefItem ref, IntItem index) {
        /*
         * helper.writePUSH(arrayRef, VmArray.LENGTH_OFFSET * slotSize);
         * os.writeDEC(SP, 0); helper.writePUSH(0); os.writeBOUND(index, SP,
         * 0); os.writeLEA(SP, SP, 8);
         */
        final Label ok = new Label(curInstrLabel + "$$cbok");
        // CMP length, index
        assertCondition(ref.getKind() == Item.REGISTER);
        final Register r = ref.getRegister();
        if (index.getKind() == Item.CONSTANT) {
            //IMPROVE: implement CMP dist[reg], imm32
            // final int val = ((IntConstant)index.getConstant()).getValue();
            index.load(eContext);
            os.writeCMP(r, VmArray.LENGTH_OFFSET * slotSize, index
                    .getRegister());
        } else {
            os.writeCMP(r, VmArray.LENGTH_OFFSET * slotSize, index
                    .getRegister());
        }
        os.writeJCC(ok, X86Constants.JA);
        // Signal ArrayIndexOutOfBounds
        os.writeINT(5);
        os.setObjectRef(ok);

        //		os.writeCMP(arrayRef, VmArray.LENGTH_OFFSET * slotSize, index);
        //		os.writeJCC(ok, X86Constants.JA);
        //		// Signal ArrayIndexOutOfBounds
        //		os.writeINT(5);
        //		os.setObjectRef(ok);
    }

    /**
     * Emit code to validate an index of a given array
     * 
     * @param arrayRef
     * @param index
     */
    //REFACTOR: remove this method
    private final void checkBounds(Register arrayRef, Register index) {
        /*
         * 
         * slotSize); os.writeDEC(SP, 0); helper.writePUSH(0);
         * os.writeBOUND(index, SP, 0);
         */
        final Label ok = new Label(curInstrLabel + "$$cbok");
        // CMP length, index
        os.writeCMP(arrayRef, VmArray.LENGTH_OFFSET * slotSize, index);
        os.writeJCC(ok, X86Constants.JA);
        // Signal ArrayIndexOutOfBounds
        os.writeINT(5);
        os.setObjectRef(ok);
    }

    /**
     * Write code to resolve the given constant field referred to by fieldRef
     * 
     * @param fieldRef
     * @param scratch
     */
    private final void writeInitializeClass(VmConstFieldRef fieldRef,
            Register scratch) {
        //TODO: port to orp-style
        // Get fieldRef via constantpool to avoid direct object references in
        // the native code
        if (scratch == EAX) { throw new IllegalArgumentException(
                "scratch cannot be equal to EAX"); }

        final VmType declClass = fieldRef.getResolvedVmField()
                .getDeclaringClass();
        if (!declClass.isInitialized()) {
            // Now look for class initialization
            // Load classRef into EAX
            // Load the class from the statics table
            helper.writeGetStaticsEntry(new Label(curInstrLabel + "$$ic"), EAX,
                    declClass);

            // Load declaringClass.typeState into scratch
            os.writeMOV(INTSIZE, scratch, EAX, context.getVmTypeState()
                    .getOffset());
            // Test for initialized
            os.writeTEST(scratch, VmTypeState.ST_INITIALIZED);
            final Label afterInit = new Label(curInstrLabel + "$$aci");
            os.writeJCC(afterInit, X86Constants.JNZ);
            // Call cls.initialize
            helper.writePUSH(EAX);
            helper.invokeJavaMethod(context.getVmTypeInitialize());
            os.setObjectRef(afterInit);
        }

    }

    /**
     * Write code to resolve the given constant class (if needed) and load the
     * resolved class (VmType instance) into EAX.
     * 
     * @param classRef
     * @param scratch
     */
    private final void writeResolveAndLoadClassToEAX(VmConstClass classRef,
            Register scratch) {
        //TODO: port to orp-style
        // Check assertConditionions
        if (scratch == EAX) { throw new IllegalArgumentException(
                "scratch cannot be equal to EAX"); }

        // Resolve the class
        classRef.resolve(loader);
        final VmType type = classRef.getResolvedVmClass();

        // Load the class from the statics table
        helper.writeGetStaticsEntry(curInstrLabel, EAX, type);
    }

    /**
     * Emit the core of the instanceof code Input: ECX objectref EAX vmType
     * 
     * @param trueLabel
     *            Where to jump for a true result. A false result will continue
     *            directly after this method
     */
    private final void instanceOf(Label trueLabel) {
        //TODO: port to orp-style
        final Label loopLabel = new Label(this.curInstrLabel + "loop");
        final Label notInstanceOfLabel = new Label(this.curInstrLabel
                + "notInstanceOf");

        /* Is objectref null? */
        os.writeTEST(ECX, ECX);
        os.writeJCC(notInstanceOfLabel, X86Constants.JZ);
        /* vmType -> edx */
        os.writeMOV(INTSIZE, Register.EDX, Register.EAX);
        /* TIB -> ESI */
        os.writeMOV(INTSIZE, Register.ESI, Register.ECX, ObjectLayout.TIB_SLOT
                * slotSize);
        /* SuperClassesArray -> ESI */
        os
                .writeMOV(INTSIZE, Register.ESI, Register.ESI,
                        (VmArray.DATA_OFFSET + TIBLayout.SUPERCLASSES_INDEX)
                                * slotSize);
        /* SuperClassesArray.length -> ECX */
        os.writeMOV(INTSIZE, Register.ECX, Register.ESI, VmArray.LENGTH_OFFSET
                * slotSize);
        /* &superClassesArray[0] -> esi */
        os.writeLEA(Register.ESI, Register.ESI, VmArray.DATA_OFFSET * slotSize);

        os.setObjectRef(loopLabel);
        /* superClassesArray[index++] -> eax */
        os.writeLODSD();
        /* Is equal? */
        os.writeCMP(Register.EAX, Register.EDX);
        os.writeJCC(trueLabel, X86Constants.JE);
        try {
            os.writeLOOP(loopLabel);
        } catch (UnresolvedObjectRefException ex) {
            throw new CompileError(ex);
        }
        os.setObjectRef(notInstanceOfLabel);
    }

    /**
     * Insert a yieldpoint into the code
     */
    public final void yieldPoint() {
        helper.writeYieldPoint(curInstrLabel);
    }
}
