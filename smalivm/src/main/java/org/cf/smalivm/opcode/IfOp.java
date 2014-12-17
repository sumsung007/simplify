package org.cf.smalivm.opcode;

import java.math.BigDecimal;
import java.util.Comparator;

import org.cf.smalivm.context.MethodState;
import org.cf.smalivm.type.UnknownValue;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction22t;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IfOp extends MethodStateOp {

    private static enum IfType {
        EQUAL,
        GREATER,
        GREATOR_OR_EQUAL,
        LESS,
        LESS_OR_EQUAL,
        NOT_EQUAL
    }

    class NumberComparator<T extends Number & Comparable> implements Comparator<T> {
        public int compare(T a, T b) throws ClassCastException {
	    return getIntegerObject(a).compareTo(getIntegerObject(b));
        }

	// TODO: Extract to helper class ?
	//
	// Attempt to save us from class-cast exceptions incase
	// a primative is passed as a parameter
	private Integer getIntegerObject(Object obj) {
	    Integer returnable = null;

            // Check if we need to actually do anything but cast
            if (obj instanceof Integer) {
                returnable = (Integer) obj;
            } else {
                // Perform proper casting for the Primatives
		if (obj instanceof Short) {
		    returnable = ((Short) obj).intValue();
		} else if (obj instanceof Byte) {
		    returnable = ((Byte) obj).intValue();
		} else if (obj instanceof Character) {
		    returnable = (int) ((Character) obj).charValue();
		} else if (obj instanceof Short) {
		    returnable = ((Short) obj).intValue();
		} else {
		    // Attempt to save us if all else fails
		    returnable = ((Integer) obj).intValue();
		}
            }

            return returnable;
	}
    }

    private static final Logger log = LoggerFactory.getLogger(IfOp.class.getSimpleName());

    private static IfType getIfType(String opName) {
        IfType result = null;
        if (opName.contains("-eq")) {
            result = IfType.EQUAL;
        } else if (opName.contains("-ne")) {
            result = IfType.NOT_EQUAL;
        } else if (opName.contains("-lt")) {
            result = IfType.LESS;
        } else if (opName.contains("-le")) {
            result = IfType.LESS_OR_EQUAL;
        } else if (opName.contains("-gt")) {
            result = IfType.GREATER;
        } else if (opName.contains("-ge")) {
            result = IfType.GREATOR_OR_EQUAL;
        }

        return result;
    }

    private static boolean isTrue(IfType ifType, int cmp) {
        boolean result = false;
        switch (ifType) {
        case EQUAL:
            result = (cmp == 0);
            break;
        case GREATER:
            result = (cmp == 1);
            break;
        case GREATOR_OR_EQUAL:
            result = (cmp >= 0);
            break;
        case LESS:
            result = (cmp == -1);
            break;
        case LESS_OR_EQUAL:
            result = (cmp <= 0);
            break;
        case NOT_EQUAL:
            result = (cmp != 0);
            break;
        }

        return result;
    }

    private static BigDecimal widenToBigDecimal(Object value) {
        // Value should be primitive wrapper (Integer, Character, Boolean, etc.)
        if (value instanceof Character) {
            value = (int) ((char) value);
        } else if (value instanceof Boolean) {
            value = ((boolean) value) ? 1 : 0;
        }
        BigDecimal bigD = new BigDecimal(value.toString());

        return bigD; // lol
    }

    static IfOp create(Instruction instruction, int address) {
        int branchOffset = ((OffsetInstruction) instruction).getCodeOffset();
        int targetAddress = address + branchOffset;
        int childAddress = address + instruction.getCodeUnits();

        String opName = instruction.getOpcode().name;
        IfType ifType = getIfType(opName);
        int register1 = ((OneRegisterInstruction) instruction).getRegisterA();

        if (instruction instanceof Instruction22t) {
            // if-* vA, vB, :label
            Instruction22t instr = (Instruction22t) instruction;

            return new IfOp(address, opName, childAddress, ifType, targetAddress, register1, instr.getRegisterB());
        } else {
            // if-*z vA, vB, :label (Instruction 21t)
            return new IfOp(address, opName, childAddress, ifType, targetAddress, register1);
        }
    }

    private boolean compareToZero;
    private final IfType ifType;
    private final NumberComparator numberComparitor;

    private final int register1;
    private int register2;

    private final int targetAddress;

    private IfOp(int address, String opName, int childAddress, IfType ifType, int targetAddress, int register1) {
        super(address, opName, new int[] { childAddress, targetAddress });

        this.ifType = ifType;
        this.targetAddress = targetAddress;
        this.register1 = register1;
        compareToZero = true;
        numberComparitor = new NumberComparator();
    }

    private IfOp(int address, String opName, int childAddress, IfType ifType, int targetAddress, int register1,
                    int register2) {
        this(address, opName, childAddress, ifType, targetAddress, register1);
        this.register2 = register2;
        compareToZero = false;
    }

    @Override
    public int[] execute(MethodState mState) {
        Object A = mState.readRegister(register1);
        Object B = 0;
        if (!compareToZero) {
            B = mState.readRegister(register2);
        }

        // Ambiguous predicate. Follow both branches.
        if ((A instanceof UnknownValue) || (B instanceof UnknownValue)) {
            return getPossibleChildren();
        }

        int cmp = Integer.MIN_VALUE;
        if (compareToZero) {
            if (A instanceof Number) {
                cmp = numberComparitor.compare((Number) A, 0);
            } else if (A instanceof Boolean) {
                cmp = numberComparitor.compare((Boolean) A ? 1 : 0, 0);
            } else {
                // if-*z ops are used to check for null refs
                cmp = A == null ? 0 : 1;
            }
        } else if (((A instanceof Number) || (A instanceof Boolean) || (A instanceof Character))
                        && ((B instanceof Number) || (B instanceof Boolean) || (B instanceof Character))) {
            BigDecimal Acmp = widenToBigDecimal(A);
            BigDecimal Bcmp = widenToBigDecimal(B);
            cmp = Acmp.compareTo(Bcmp);
        } else {
            cmp = A == B ? 0 : 1;
        }

        log.trace("IF compare: " + A + " vs " + B + " = " + cmp);

        int result = getPossibleChildren()[0];
        if (isTrue(ifType, cmp)) {
            result = targetAddress;
        }

        return new int[] { result };
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getName());

        sb.append(" r").append(register1);
        if (!compareToZero) {
            sb.append(", r").append(register2);
        }
        sb.append(", #").append(targetAddress);

        return sb.toString();
    }

}
