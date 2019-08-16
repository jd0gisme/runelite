package net.runelite.injector.raw;

import com.google.common.base.Stopwatch;
import net.runelite.asm.ClassFile;
import net.runelite.asm.pool.Class;
import net.runelite.injector.Inject;
import net.runelite.injector.InjectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExtendsApplet
{
	private final Logger log = LoggerFactory.getLogger(ExtendsApplet.class);
	private final Inject inject;

	public ExtendsApplet(Inject inject)
	{
		this.inject = inject;
	}

	public void inject() throws InjectionException
	{
		Stopwatch stopwatch = Stopwatch.createStarted();

		found:
		{
			for (ClassFile cf : inject.getVanilla().getClasses())
			{
				try
				{
					if (cf.getParentClass().getName().contains("java/applet/Applet"))
					{
						cf.setParentClass(new Class("org/jdesktop/swingx/JXApplet"));
						break found;
					}
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}
			}
			throw new InjectionException("Could not find Applet class");
		}

		stopwatch.stop();
		log.info("ExtendsApplet took {}", stopwatch.toString());
	}
}
