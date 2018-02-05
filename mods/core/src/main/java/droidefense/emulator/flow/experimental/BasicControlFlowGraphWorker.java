package droidefense.emulator.flow.experimental;


import droidefense.emulator.flow.stable.SimpleFlowWorker;
import droidefense.emulator.machine.base.AbstractDVMThread;
import droidefense.emulator.machine.base.struct.fake.DVMTaintClass;
import droidefense.emulator.machine.base.struct.fake.DVMTaintMethod;
import droidefense.emulator.machine.base.struct.generic.IDroidefenseClass;
import droidefense.emulator.machine.base.struct.generic.IDroidefenseFrame;
import droidefense.emulator.machine.base.struct.generic.IDroidefenseMethod;
import droidefense.emulator.machine.inst.DalvikInstruction;
import droidefense.emulator.machine.inst.InstructionReturn;
import droidefense.log4j.Log;
import droidefense.log4j.LoggerType;
import droidefense.rulengine.map.BasicCFGFlowMap;
import droidefense.sdk.model.base.DroidefenseProject;

import java.util.Vector;

public final strictfp class BasicControlFlowGraphWorker extends SimpleFlowWorker {

    private int[] opCodes;
    private int[] registerCodes;
    private int[] codes;

    public BasicControlFlowGraphWorker(DroidefenseProject project) {
        super(project);
        this.name = "BasicControlFlowGraphWorker";
        flowMap = new BasicCFGFlowMap();
        fromNode = null;
    }

    @Override
    public void finish() {
        currentProject.setNormalControlFlowMap(flowMap);
        Log.write(LoggerType.DEBUG, "WORKER: BasicControlFlowGraphWorker FINISHED!");
    }

    @Override
    public strictfp void execute(boolean keepScanning) throws Throwable {

        boolean methodEnded = false;
        IDroidefenseFrame frame = getCurrentFrame();
        if(frame!=null){
            IDroidefenseMethod method = frame.getMethod();

            opCodes = method.getOpcodes();
            registerCodes = method.getRegisterOpcodes();
            codes = method.getIndex();

            keepScanning = true;
            methodEnded = false;
        }
        else{
            keepScanning=false;
        }

        while (keepScanning) {

            int currentPc = frame.getPc();
            int currentInstructionOpcode;

            //1 ask if we have more currentInstructionOpcode to execute
            boolean noMoreInstructions = currentPc >= opCodes.length || getFrames() == null || getFrames().isEmpty();
            if (noMoreInstructions) {
                keepScanning = false;
                break;
            }

            currentInstructionOpcode = opCodes[currentPc];
            DalvikInstruction currentInstruction = AbstractDVMThread.instructions[currentInstructionOpcode];
            Log.write(LoggerType.TRACE, currentInstruction.name() + " " + currentInstruction.description());

            frame.increasePc(currentInstruction.fakePcIncrement());


        }
    }

    private boolean goBack(int fakePc) {

        //remove last frame and set the new one the last one
        IDroidefenseFrame supposedPreviousFrame = null;
        Vector list = getCurrentFrame().getThread().getFrames();
        if (list != null && !list.isEmpty()) {
            list.remove(list.size() - 1);
            if (!list.isEmpty()) {
                //set current frame list lastone
                supposedPreviousFrame = (IDroidefenseFrame) list.get(list.size() - 1);
            } else {
                //no las frame, set null;
                supposedPreviousFrame = null;
            }
        }

        if (supposedPreviousFrame != null) {
            //set as current frame
            replaceCurrentFrame(supposedPreviousFrame);
            //reload method
            getCurrentFrame().setMethod(supposedPreviousFrame.getMethod());

            //restore codes
            opCodes = getCurrentFrame().getMethod().getOpcodes();
            registerCodes = getCurrentFrame().getMethod().getRegisterOpcodes();
            codes = getCurrentFrame().getMethod().getIndex();
            getCurrentFrame().increasePc(fakePc);
            return true;
        }

        return false;
    }

    private InstructionReturn fakeMethodCall(IDroidefenseFrame frame) {

        IDroidefenseMethod method = frame.getMethod();

        // invoke-virtual {vD, vE, vF, vG, vA}, meth@CCCC
        int registersBase = registerCodes[frame.increasePc()];
        int registers = registersBase << 16;
        int methodIndex = codes[frame.increasePc()];
        registers |= codes[frame.increasePc()];

        //Todo fix null pointer when calling this method using tainted classes

        String clazzName;
        String methodName;
        String methodDescriptor;
        if (method.isFake()) {
            clazzName = method.getOwnerClass().getName();
            methodName = method.getName();
            methodDescriptor = method.getDescriptor();
        } else {
            clazzName = method.getMethodClasses()[methodIndex];
            methodName = method.getMethodNames()[methodIndex];
            methodDescriptor = method.getMethodTypes()[methodIndex];
        }

        IDroidefenseClass cls = new DVMTaintClass(clazzName);
        return getInstructionReturn(clazzName, methodName, methodDescriptor, cls);
    }

    private InstructionReturn getInstructionReturn(String clazzName, String methodName, String methodDescriptor, IDroidefenseClass cls) {
        IDroidefenseMethod methodToCall = cls.getMethod(methodName, methodDescriptor, false);
        //if class is an interface, It will not have the method to be called
        if (methodToCall == null) {
            methodToCall = new DVMTaintMethod(methodName, clazzName);
            methodToCall.setDescriptor(methodDescriptor);
            methodToCall.setOwnerClass(cls);
        }
        IDroidefenseFrame frame = callMethod(false, methodToCall, getCurrentFrame());
        int[] lowerCodes = methodToCall.getOpcodes();
        int[] upperCodes = methodToCall.getRegisterOpcodes();
        int[] codes = methodToCall.getIndex();
        return new InstructionReturn(frame, methodToCall, lowerCodes, upperCodes, codes, null);
    }
}