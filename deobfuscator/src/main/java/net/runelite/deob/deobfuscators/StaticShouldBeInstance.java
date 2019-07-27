package net.runelite.deob.deobfuscators;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import net.runelite.asm.ClassFile;
import net.runelite.asm.ClassGroup;
import net.runelite.asm.Field;
import net.runelite.asm.Type;
import net.runelite.asm.attributes.Annotations;
import net.runelite.asm.attributes.Code;
import net.runelite.asm.attributes.code.Instruction;
import net.runelite.asm.attributes.code.Instructions;
import net.runelite.asm.attributes.code.instruction.types.LVTInstruction;
import net.runelite.asm.attributes.code.instruction.types.ReturnInstruction;
import net.runelite.asm.attributes.code.instructions.ALoad;
import net.runelite.asm.attributes.code.instructions.IfNonNull;
import net.runelite.asm.attributes.code.instructions.IfNull;
import net.runelite.asm.attributes.code.instructions.InvokeStatic;
import net.runelite.asm.attributes.code.instructions.InvokeVirtual;
import net.runelite.asm.pool.Method;
import net.runelite.asm.signature.Signature;
import net.runelite.asm.signature.Signature.Builder;
import net.runelite.deob.Deobfuscator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StaticShouldBeInstance implements Deobfuscator
{
	private static final Logger logger = LoggerFactory.getLogger(StaticShouldBeInstance.class);
	// map between old & new
	private final Map<Method, Method> methods = new HashMap<>();
	// method -> where the new method should be
	private final Set<Method> toRemove = new HashSet<>();

	public void run(ClassGroup group)
	{
		int replacedCalls = 0;
		int removedInstructions = 0;
		int removedMethods = 0;
		int removedAnnotations = 0;
		List<net.runelite.asm.Method> obfuscatedMethods = new ArrayList<>();

		for (ClassFile cf : group.getClasses())
		{
			// Remove unused annotations
			Annotations a = cf.getAnnotations();
			removedAnnotations += a.getAnnotations().size();
			a.clearAnnotations();

			obfuscatedMethods.clear();
			toRemove.clear();

			for (net.runelite.asm.Method m : cf.getMethods())
			{
				// Remove unused annotations
				a = m.getAnnotations();
				removedAnnotations += a.size();
				a.clearAnnotations();

				if (m.isStatic() && m.getCode() != null
					&& checkIfObf(m))
				{
					obfuscatedMethods.add(m);
				}
			}

			for (net.runelite.asm.Method m : obfuscatedMethods)
			{
				final Signature sig = m.getDescriptor();
				Builder builder = new Builder();
				builder.setReturnType(sig.getReturnValue());
				if (sig.getArguments().size() > 1)
				{
					builder.addArguments(sig.getArguments().subList(1, sig.getArguments().size()));
				}

				final Signature target = builder.build();

				if (hasToBeMoved(sig, m))
				{
					final ClassFile realClass = group.findClass(sig.getTypeOfArg(0).getInternalName());
					final net.runelite.asm.Method newMethod = new net.runelite.asm.Method(realClass, m.getName(), target);
					final Code newCode = new Code(newMethod);
					final Instructions newInstructions = newCode.getInstructions();

					newInstructions.getInstructions().addAll(m.getCode().getInstructions().getInstructions());
					// Update instructions for each instruction
					for (Instruction i : newInstructions.getInstructions())
					{
						i.setInstructions(newInstructions);
					}
					newCode.getExceptions().getExceptions().addAll(m.getCode().getExceptions().getExceptions());
					for (net.runelite.asm.attributes.code.Exception e : newCode.getExceptions().getExceptions())
					{
						e.setExceptions(newCode.getExceptions());
					}
					newMethod.setCode(newCode);
					newMethod.setAccessFlags(m.getAccessFlags());
					newMethod.setStatic(false);

/*
					boolean passedReturn = false;
					for (Instruction i : m.getCode().getInstructions().getInstructions())
					{
						if (passedReturn)
						{
							final Instruction newI = i.clone();
							newI.setInstructions(newInstructions);
							newInstructions.addInstruction(newI);
							continue;
						}

						removedInstructions++;

						if (i instanceof ReturnInstruction)
						{
							passedReturn = true;
						}
					}*/

					realClass.addMethod(newMethod);
					int startLength = newInstructions.getInstructions().size();
					ListIterator<Instruction> it = newInstructions.getInstructions().listIterator();
					assert it.hasNext();
					Instruction i = it.next();
					while (!(i instanceof ReturnInstruction))
					{
						it.remove();
						i = it.next();
					}
					it.remove();
					methods.put(m.getPoolMethod(), newMethod.getPoolMethod());
					cf.removeMethod(m);
					removedInstructions += startLength - newInstructions.getInstructions().size();
					continue;
				}

				net.runelite.asm.pool.Method oldPool = m.getPoolMethod();

				m.setDescriptor(target);
				m.setStatic(false);
				Code c = m.getCode();
				Instructions ins = c.getInstructions();
				int startLength = ins.getInstructions().size();
				ListIterator<Instruction> it = ins.getInstructions().listIterator();
				assert it.hasNext();
				Instruction i = it.next();
				while (!(i instanceof ReturnInstruction))
				{
					it.remove();
					i = it.next();
				}
				it.remove();
				net.runelite.asm.pool.Method newPool = m.getPoolMethod();

				methods.put(oldPool, newPool);

				removedInstructions += startLength - ins.getInstructions().size();
			}

			for (Field fi : cf.getFields())
			{
				a = fi.getAnnotations();
				if (a.find(new Type("Ljavax/inject/Inject;")) == null)
				{
					removedAnnotations += a.size();
					a.clearAnnotations();
				}
				else
				{
					logger.info("Class {}, field {} has inject", cf.getClassName(), fi.getName());
				}
			}
		}

		for (Method m : toRemove)
		{
			final ClassFile cf = group.findClass(m.getClazz().getName());
			final net.runelite.asm.Method method = cf.findMethod(m.getName(), m.getType());
			cf.removeMethod(method);
			removedMethods++;
		}

		for (ClassFile cf : group.getClasses())
		{
			for (net.runelite.asm.Method m : cf.getMethods())
			{
				if (m.getCode() == null)
				{
					continue;
				}

				Instructions ins = m.getCode().getInstructions();
				List<Instruction> instructions = ins.getInstructions();
				for (int i1 = 0, instructionsSize = instructions.size(); i1 < instructionsSize; i1++)
				{
					Instruction i = instructions.get(i1);
					if (!(i instanceof InvokeStatic))
					{
						continue;
					}

					if (methods.containsKey(((InvokeStatic) i).getMethod()))
					{
						InvokeVirtual invoke = new InvokeVirtual(ins, methods.get(((InvokeStatic) i).getMethod()));
						ins.replace(i, invoke);
						replacedCalls++;
					}
				}
			}
		}

		logger.info("Made {} methods not static, removed {} instructions, replaced {} invokes, and removed {} annotations", removedMethods, removedInstructions, replacedCalls, removedAnnotations);
	}

	private boolean checkIfObf(net.runelite.asm.Method m)
	{
		Signature sig = m.getDescriptor();
		if (sig.getArguments().size() < 1)
		{
			return false;
		}

		final Type type = sig.getTypeOfArg(0);

		if (type.isPrimitive() || type.isArray())
		{
			return false;
		}

		final Code c = m.getCode();
		final Instructions ins = c.getInstructions();
		final List<Instruction> instructions = ins.getInstructions();
		int idx = 0;
		while (idx < instructions.size())
		{
			Instruction i = instructions.get(idx++);

			if (i instanceof IfNull && isALoadForIdxZero(instructions.get(idx - 2)))
			{
				idx = instructions.indexOf(((IfNull) i).getTo());
				i = instructions.get(idx++);
				if (isALoadForIdxZero(i) && invokeComingUp(instructions, idx))
				{
					return true;
				}
			}
			else if (i instanceof IfNonNull && isALoadForIdxZero(instructions.get(idx - 2)))
			{
				return isALoadForIdxZero(instructions.get(idx++)) && invokeComingUp(instructions, idx);
			}
			else
			{
				continue;
			}

			return isALoadForIdxZero(instructions.get(idx++)) && invokeComingUp(instructions, idx);
		}

		return false;
	}

	private boolean isALoadForIdxZero(final Instruction i)
	{
		return i instanceof ALoad && ((ALoad) i).getVariableIndex() == 0;
	}

	private boolean invokeComingUp(final List<Instruction> instructions, int idx)
	{
		while (instructions.get(idx++) instanceof LVTInstruction);

		// add target method to toRemove, else we're gonna have to look for it again later
		// the static method has the original code, instance does not
		return instructions.get(idx - 1) instanceof InvokeVirtual && toRemove.add(((InvokeVirtual) instructions.get(idx - 1)).getMethod());
	}

	private boolean hasToBeMoved(Signature sig, net.runelite.asm.Method m)
	{
		return !sig.getTypeOfArg(0).equals(new Type('L' + m.getClassFile().getName() + ';'));
	}
}
