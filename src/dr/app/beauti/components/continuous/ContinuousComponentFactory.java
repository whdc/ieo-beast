package dr.app.beauti.components.continuous;

import dr.app.beauti.components.ComponentFactory;
import dr.app.beauti.generator.ComponentGenerator;
import dr.app.beauti.options.BeautiOptions;
import dr.app.beauti.options.ComponentOptions;

/**
 * @author Andrew Rambaut
 * @version $Id: ContinuousComponentFactory.java 4316 2011-09-15 15:04:12Z rambaut $
 */

public class ContinuousComponentFactory implements ComponentFactory {

	public static ComponentFactory INSTANCE = new ContinuousComponentFactory();

	private ContinuousComponentGenerator generator = null;
	private ContinuousComponentOptions options = null;

	public ComponentGenerator getGenerator(BeautiOptions beautiOptions) {
		if (generator == null) {
			generator = new ContinuousComponentGenerator(beautiOptions);
		}
		return generator;
	}

	public ComponentOptions getOptions(BeautiOptions beautiOptions) {
		if (options == null) {
			options = new ContinuousComponentOptions(beautiOptions);
		}
		return options;
	}

}