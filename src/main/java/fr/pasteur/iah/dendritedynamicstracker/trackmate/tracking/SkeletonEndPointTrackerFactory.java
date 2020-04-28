package fr.pasteur.iah.dendritedynamicstracker.trackmate.tracking;

import static fiji.plugin.trackmate.io.IOUtils.readDoubleAttribute;
import static fiji.plugin.trackmate.io.IOUtils.writeAttribute;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALTERNATIVE_LINKING_COST_FACTOR;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_LINKING_MAX_DISTANCE;
import static fiji.plugin.trackmate.util.TMUtils.checkMapKeys;
import static fiji.plugin.trackmate.util.TMUtils.checkParameter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.ImageIcon;

import org.jdom2.Element;
import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.gui.ConfigurationPanel;
import fiji.plugin.trackmate.gui.panels.tracker.SimpleLAPTrackerSettingsPanel;
import fiji.plugin.trackmate.tracking.SpotTracker;
import fiji.plugin.trackmate.tracking.SpotTrackerFactory;
import fiji.plugin.trackmate.tracking.TrackerKeys;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.JunctionIDAnalyzerFactory;

@Plugin( type = SpotTrackerFactory.class )
public class SkeletonEndPointTrackerFactory implements SpotTrackerFactory
{
	public static final String SKELETON_TRACKER_KEY = "SKELETON_END_POINT_TRACKER";

	public static final String SKELETON_TRACKER_NAME = "Skeleton end-point tracker";

	public static final String SKELETON_TRACKER_INFO_TEXT = "<html>"
			+ "This tracker tracks end-points of a skeleton."
			+ "<p>"
			+ "It privilieges matching end-points that connect to the same junction track. <br>"
			+ "Of course, this junction track ID must have been processed before, "
			+ " and stored in the JunctionIDAnalyzerFactory feature."
			+ " </html>";

	/**
	 * Key for the parameter that specifies by how much to decrease the cost of
	 * linking two skeleton end-points that connect to the same junction track.
	 * For instance if the distance-based cost to link e1 and e2 is 250 but they
	 * are connected to the same junction track, as tracked by the junction
	 * track ID stored in the {@link JunctionIDAnalyzerFactory#FEATURE}, the
	 * actual linking cost will be 250 divided by the factor stored in this
	 * parameter.
	 */
	public static final String KEY_MATCHED_COST_FACTOR = "MATCHED_COST_FACTOR";

	/**
	 * Default value for {@link #KEY_MATCHED_COST_FACTOR}.
	 */
	public static final Double DEFAULT_MATCHED_COST_FACTOR = Double.valueOf( 10. );

	private String errorMessage;

	@Override
	public String getKey()
	{
		return SKELETON_TRACKER_KEY;
	}

	@Override
	public String getName()
	{
		return SKELETON_TRACKER_NAME;
	}

	@Override
	public String getInfoText()
	{
		return SKELETON_TRACKER_INFO_TEXT;
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
		return new SimpleLAPTrackerSettingsPanel( getName(), SKELETON_TRACKER_INFO_TEXT, spaceUnits );
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
		ok = ok & checkParameter( settings, KEY_MATCHED_COST_FACTOR, Double.class, errorHolder );
		// Check keys
		final List<String> mandatoryKeys = new ArrayList<>();
		mandatoryKeys.add(KEY_LINKING_MAX_DISTANCE);
		mandatoryKeys.add(KEY_ALTERNATIVE_LINKING_COST_FACTOR);
		mandatoryKeys.add(KEY_MATCHED_COST_FACTOR);
		final List<String> optionalKeys = new ArrayList<>();
		ok = ok & checkMapKeys( settings, mandatoryKeys, optionalKeys, errorHolder );

		if ( !ok )
			errorMessage = errorHolder.toString();

		return ok;
	}

	@Override
	public boolean marshall( final Map< String, Object > settings, final Element element )
	{
		boolean ok = true;
		final StringBuilder str = new StringBuilder();
		ok = ok & writeAttribute( settings, element, KEY_LINKING_MAX_DISTANCE, Double.class, str );
		ok = ok & writeAttribute( settings, element, KEY_ALTERNATIVE_LINKING_COST_FACTOR, Double.class, str );
		ok = ok & writeAttribute( settings, element, KEY_MATCHED_COST_FACTOR, Double.class, str );
		return ok;
	}

	@Override
	public boolean unmarshall( final Element element, final Map< String, Object > settings )
	{
		settings.clear();
		final StringBuilder errorHolder = new StringBuilder();
		boolean ok = true;
		ok = ok & readDoubleAttribute( element, settings, KEY_LINKING_MAX_DISTANCE, errorHolder );
		ok = ok & readDoubleAttribute( element, settings, KEY_ALTERNATIVE_LINKING_COST_FACTOR, errorHolder );
		ok = ok & readDoubleAttribute( element, settings, KEY_MATCHED_COST_FACTOR, errorHolder );

		if ( !checkSettingsValidity( settings ) )
		{
			ok = false;
			errorHolder.append( errorMessage ); // append validity check message
		}

		if ( !ok )
			errorMessage = errorHolder.toString();

		return ok;
	}

	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}

	@Override
	public ImageIcon getIcon()
	{
		return null;
	}

	@Override
	public String toString( final Map< String, Object > sm )
	{
		if ( !checkSettingsValidity( sm ) )
			return errorMessage;

		final StringBuilder str = new StringBuilder();
		str.append( String.format( "    - max distance: %.1f\n", ( Double ) sm.get( KEY_LINKING_MAX_DISTANCE ) ) );
		str.append( String.format( "    - matched-cost factor: %.1f\n", ( Double ) sm.get( KEY_MATCHED_COST_FACTOR ) ) );

		return str.toString();
	}

	@Override
	public Map< String, Object > getDefaultSettings()
	{
		final Map< String, Object > trackerSettings = new HashMap<>();
		trackerSettings.put( TrackerKeys.KEY_LINKING_MAX_DISTANCE, Double.valueOf( 5. ) );
		trackerSettings.put( TrackerKeys.KEY_ALTERNATIVE_LINKING_COST_FACTOR, Double.valueOf( TrackerKeys.DEFAULT_ALTERNATIVE_LINKING_COST_FACTOR ) );
		trackerSettings.put( SkeletonEndPointTrackerFactory.KEY_MATCHED_COST_FACTOR, Double.valueOf( 10. ) );
		return trackerSettings;
	}
}
