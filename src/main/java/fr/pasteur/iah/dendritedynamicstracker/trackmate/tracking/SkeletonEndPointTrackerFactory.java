package fr.pasteur.iah.dendritedynamicstracker.trackmate.tracking;

import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALTERNATIVE_LINKING_COST_FACTOR;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_LINKING_MAX_DISTANCE;
import static fiji.plugin.trackmate.util.TMUtils.checkMapKeys;
import static fiji.plugin.trackmate.util.TMUtils.checkParameter;

import java.util.ArrayList;
import java.util.List;
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
public class SkeletonEndPointTrackerFactory extends SparseLAPTrackerFactory
{
	public static final String THIS2_TRACKER_KEY = "SKELETON_END_POINT_TRACKER";

	public static final String THIS2_NAME = "Skeleton end-point tracker";

	public static final String THIS2_INFO_TEXT = "<html>"
			+ "This tracker tracks end-points of a skeleton."
			+ "<p>"
			+ "It privilieges matching end-points that connect to the same junction track. <br>"
			+ "Of course, this junction track ID must have been processed before, "
			+ " and stored in the JunctionIDAnalyzerFactory feature."
			+ " </html>";

	private String errorMessage;

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
		return new SkeletonEndPointTracker( spots, settings );
	}

	@Override
	public ConfigurationPanel getTrackerConfigurationPanel( final Model model )
	{
		final String spaceUnits = model.getSpaceUnits();
		return new SimpleLAPTrackerSettingsPanel( getName(), THIS2_INFO_TEXT, spaceUnits );
	}


	@Override
	public boolean checkSettingsValidity( final Map< String, Object > settings )
	{
		errorMessage = null;

		if ( null == settings )
		{
			errorMessage = "Settings map is null.\n";
			return false;
		}

		final StringBuilder errorHolder = new StringBuilder();
		boolean ok = true;
		// Linking
		ok = ok & checkParameter( settings, KEY_LINKING_MAX_DISTANCE, Double.class, errorHolder );
		ok = ok & checkParameter( settings, KEY_ALTERNATIVE_LINKING_COST_FACTOR, Double.class, errorHolder );
		// Check keys
		final List<String> mandatoryKeys = new ArrayList<>();
		mandatoryKeys.add(KEY_LINKING_MAX_DISTANCE);
		mandatoryKeys.add(KEY_ALTERNATIVE_LINKING_COST_FACTOR);
		final List<String> optionalKeys = new ArrayList<>();
		ok = ok & checkMapKeys( settings, mandatoryKeys, optionalKeys, errorHolder );

		if ( !ok )
			errorMessage = errorHolder.toString();

		return ok;
	}

	@Override
	public String getErrorMessage()
	{
		if (null != errorMessage)
			return errorMessage;

		return super.getErrorMessage();
	}

}
