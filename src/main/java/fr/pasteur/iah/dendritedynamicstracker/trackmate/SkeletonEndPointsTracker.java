package fr.pasteur.iah.dendritedynamicstracker.trackmate;

import static fiji.plugin.trackmate.tracking.LAPUtils.checkFeatureMap;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALLOW_GAP_CLOSING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALLOW_TRACK_MERGING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALLOW_TRACK_SPLITTING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALTERNATIVE_LINKING_COST_FACTOR;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_BLOCKING_VALUE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_CUTOFF_PERCENTILE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_LINKING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_LINKING_MAX_DISTANCE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_MERGING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_MERGING_MAX_DISTANCE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_SPLITTING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_SPLITTING_MAX_DISTANCE;
import static fiji.plugin.trackmate.util.TMUtils.checkMapKeys;
import static fiji.plugin.trackmate.util.TMUtils.checkParameter;
import static fr.pasteur.iah.dendritedynamicstracker.trackmate.SkeletonEndPointsDetector.END_POINTS_QUALITY_VALUE;
import static fr.pasteur.iah.dendritedynamicstracker.trackmate.SkeletonEndPointsDetector.JUNCTION_POINTS_QUALITY_VALUE;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.tracking.SpotTracker;
import fiji.plugin.trackmate.tracking.sparselap.SparseLAPTracker;
import net.imglib2.algorithm.MultiThreadedBenchmarkAlgorithm;

public class SkeletonEndPointsTracker extends MultiThreadedBenchmarkAlgorithm implements SpotTracker
{

	private final SpotCollection spots;

	private final Map< String, Object > settings;

	private final static String BASE_ERROR_MESSAGE = "[SparseLAPTracker] ";

	private SimpleWeightedGraph< Spot, DefaultWeightedEdge > graph;

	private Logger logger = Logger.VOID_LOGGER;

	public SkeletonEndPointsTracker( final SpotCollection spots, final Map< String, Object > settings )
	{
		this.spots = spots;
		this.settings = settings;
	}

	@Override
	public SimpleWeightedGraph< Spot, DefaultWeightedEdge > getResult()
	{
		return graph;
	}

	@Override
	public boolean checkInput()
	{
		return true;
	}

	@Override
	public boolean process()
	{

		/*
		 * Check input now.
		 */

		// Check that the objects list itself isn't null
		if ( null == spots )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The spot collection is null.";
			return false;
		}

		// Check that the objects list contains inner collections.
		if ( spots.keySet().isEmpty() )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The spot collection is empty.";
			return false;
		}

		// Check that at least one inner collection contains an object.
		boolean empty = true;
		for ( final int frame : spots.keySet() )
		{
			if ( spots.getNSpots( frame, true ) > 0 )
			{
				empty = false;
				break;
			}
		}
		if ( empty )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The spot collection is empty.";
			return false;
		}
		// Check parameters
		final StringBuilder errorHolder = new StringBuilder();
		if ( !checkSettingsValidity( settings, errorHolder ) )
		{
			errorMessage = BASE_ERROR_MESSAGE + "Incorrect settings map:\n" + errorHolder.toString();
			return false;
		}

		/*
		 * Process.
		 */

		final long start = System.currentTimeMillis();

		// Instantiate graph
		graph = new SimpleWeightedGraph<>( DefaultWeightedEdge.class );

		/*
		 * First track the junctions.
		 */

		if ( !trackClass( JUNCTION_POINTS_QUALITY_VALUE ) )
			return false;

		/*
		 * Second track the end-points;
		 */

		if ( !trackClass( END_POINTS_QUALITY_VALUE ) )
			return false;

		logger.setStatus( "" );
		logger.setProgress( 1d );
		final long end = System.currentTimeMillis();
		processingTime = end - start;

		return true;
	}

	private boolean trackClass( final double qualityValue )
	{
		// Lookup all spots with desired quality value.
		final SpotCollection filtered = new SpotCollection();
		for ( final Spot spot : spots.iterable( true ) )
		{
			if ( spot.getFeature( Spot.QUALITY ).doubleValue() == qualityValue )
			{
				filtered.add( spot, spot.getFeature( Spot.FRAME ).intValue() );
				graph.addVertex( spot );
			}
		}
		
		// Track them.
		final SparseLAPTracker tracker = new SparseLAPTracker( filtered, settings );
		tracker.setLogger( logger );
		tracker.setNumThreads( getNumThreads() );
		if ( !tracker.checkInput() || !tracker.process() )
		{
			errorMessage = tracker.getErrorMessage();
			return false;
		}

		// Copy results to core graph.
		final SimpleWeightedGraph< Spot, DefaultWeightedEdge > localGraph = tracker.getResult();
		for ( final DefaultWeightedEdge localEdge : localGraph.edgeSet() )
		{
			final DefaultWeightedEdge edge = graph.addEdge( localGraph.getEdgeSource( localEdge ), localGraph.getEdgeTarget( localEdge ) );
			graph.setEdgeWeight( edge, localGraph.getEdgeWeight( localEdge ) );
		}

		return true;
	}

	@Override
	public void setLogger( final Logger logger )
	{
		this.logger = logger;
	}
	

	private static final boolean checkSettingsValidity( final Map< String, Object > settings, final StringBuilder str )
	{
		if ( null == settings )
		{
			str.append( "Settings map is null.\n" );
			return false;
		}

		boolean ok = true;
		// Linking
		ok = ok & checkParameter( settings, KEY_LINKING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkFeatureMap( settings, KEY_LINKING_FEATURE_PENALTIES, str );
		// Gap-closing
		ok = ok & checkParameter( settings, KEY_ALLOW_GAP_CLOSING, Boolean.class, str );
		ok = ok & checkParameter( settings, KEY_GAP_CLOSING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkParameter( settings, KEY_GAP_CLOSING_MAX_FRAME_GAP, Integer.class, str );
		ok = ok & checkFeatureMap( settings, KEY_GAP_CLOSING_FEATURE_PENALTIES, str );
		// Splitting
		ok = ok & checkParameter( settings, KEY_ALLOW_TRACK_SPLITTING, Boolean.class, str );
		ok = ok & checkParameter( settings, KEY_SPLITTING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkFeatureMap( settings, KEY_SPLITTING_FEATURE_PENALTIES, str );
		// Merging
		ok = ok & checkParameter( settings, KEY_ALLOW_TRACK_MERGING, Boolean.class, str );
		ok = ok & checkParameter( settings, KEY_MERGING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkFeatureMap( settings, KEY_MERGING_FEATURE_PENALTIES, str );
		// Others
		ok = ok & checkParameter( settings, KEY_CUTOFF_PERCENTILE, Double.class, str );
		ok = ok & checkParameter( settings, KEY_ALTERNATIVE_LINKING_COST_FACTOR, Double.class, str );

		// Check keys
		final List< String > mandatoryKeys = new ArrayList< >();
		mandatoryKeys.add( KEY_LINKING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_ALLOW_GAP_CLOSING );
		mandatoryKeys.add( KEY_GAP_CLOSING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_GAP_CLOSING_MAX_FRAME_GAP );
		mandatoryKeys.add( KEY_ALLOW_TRACK_SPLITTING );
		mandatoryKeys.add( KEY_SPLITTING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_ALLOW_TRACK_MERGING );
		mandatoryKeys.add( KEY_MERGING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_ALTERNATIVE_LINKING_COST_FACTOR );
		mandatoryKeys.add( KEY_CUTOFF_PERCENTILE );
		final List< String > optionalKeys = new ArrayList< >();
		optionalKeys.add( KEY_LINKING_FEATURE_PENALTIES );
		optionalKeys.add( KEY_GAP_CLOSING_FEATURE_PENALTIES );
		optionalKeys.add( KEY_SPLITTING_FEATURE_PENALTIES );
		optionalKeys.add( KEY_MERGING_FEATURE_PENALTIES );
		optionalKeys.add( KEY_BLOCKING_VALUE );
		ok = ok & checkMapKeys( settings, mandatoryKeys, optionalKeys, str );

		return ok;
	}

}
