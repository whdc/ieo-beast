package dr.app.beauti.components.dollo;

import dr.app.beauti.components.ComponentFactory;
import dr.app.beauti.generator.ComponentGenerator;
import dr.app.beauti.options.BeautiOptions;
import dr.app.beauti.options.ComponentOptions;

/**
 * @author Marc Suchard
 * @version $Id: DolloComponentFactory.java 4299 2011-09-08 18:05:20Z msuchard $
 */

public class DolloComponentFactory implements ComponentFactory {

	public static ComponentFactory INSTANCE = new DolloComponentFactory();

	private DolloComponentGenerator generator = null;
	private DolloComponentOptions options = null;

	public ComponentGenerator getGenerator(BeautiOptions beautiOptions) {
		if (generator == null) {
			generator = new DolloComponentGenerator(beautiOptions);
		}
		return generator;
	}

	public ComponentOptions getOptions(BeautiOptions beautiOptions) {
		if (options == null) {
			options = new DolloComponentOptions(beautiOptions);
		}
		return options;
	}

}