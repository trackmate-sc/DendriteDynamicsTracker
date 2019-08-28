package fr.pasteur.iah.dendritedynamicstracker.trackmate;

import java.util.Map;

import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.gui.ConfigurationPanel;
import fiji.plugin.trackmate.gui.panels.tracker.SimpleLAPTrackerSettingsPanel;
import fiji.plugin.trackmate.tracking.SpotTracker;
import fiji.plugin.trackmate.tracking.SpotTrackerFactory;
import fiji.plugin.trackmate.tracking.sparselap.SparseLAPTrackerFactory;

@Plugin( type = SpotTrackerFactory.class )
public class SkeletonEndPointsTrackerFactory extends SparseLAPTrackerFactory
{
	public static final String THIS2_TRACKER_KEY = "SKELETON_END_POINTS_TRACKER";

	public static final String THIS2_NAME = "Skeleton end-points tracker";

	public static final String THIS2_INFO_TEXT = "<html>"
			+ "This tracker tracks separately the end-points and the junctions of a skeleton <br>"
			+ "detected with the Skeleton-end-points detector. <br>"
			+ "It relies on the Simple LAP tracker to do so, and have therefore identical  <br>"
			+ "parameters."
			+ " </html>";

	@Override
	public String getKey()
	{
		return THIS2_TRACKER_KEY;
	}

	@Override
	public String getName()
	{
		return THIS2_NAME;
	}

	@Override
	public String getInfoText()
	{
		return THIS2_INFO_TEXT;
	}

	@Override
	public SpotTracker create( final SpotCollection spots, final Map< String, Object > settings )
	{
		return new SkeletonEndPointsTracker( spots, settings );
	}

	@Override
	public ConfigurationPanel getTrackerConfigurationPanel( final Model model )
	{
		final String spaceUnits = model.getSpaceUnits();
		return new SimpleLAPTrackerSettingsPanel( getName(), THIS2_INFO_TEXT, spaceUnits );
	}

}
