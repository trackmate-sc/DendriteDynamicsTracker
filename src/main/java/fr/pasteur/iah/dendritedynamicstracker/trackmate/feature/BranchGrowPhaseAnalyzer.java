package fr.pasteur.iah.dendritedynamicstracker.trackmate.feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.ImageIcon;

import org.jgrapht.graph.DefaultWeightedEdge;

import fiji.plugin.trackmate.Dimension;
import fiji.plugin.trackmate.FeatureModel;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.features.edges.EdgeAnalyzer;
import fiji.plugin.trackmate.features.track.TrackSpotQualityFeatureAnalyzer;
import fr.pasteur.iah.dendritedynamicstracker.SkeletonKeyPointsDetector;
import net.imglib2.multithreading.SimpleMultiThreading;

@SuppressWarnings( "deprecation" )
public class BranchGrowPhaseAnalyzer implements EdgeAnalyzer
{
	public static final String KEY = "End-point growth phase";

	/*
	 * FEATURE NAMES
	 */

	/**
	 * Growing = 1. Shrinking = -1. Static = 0.
	 */
	public static final String GROWTH_PHASE = "GROWTH_PHASE";

	public static final String GROWTH = "GROWTH";

	/**
	 * In radians.
	 */
	public static final String GROWTH_ANGLE = "GROWTH_ANGLE";

	public static final Integer GROWING = Integer.valueOf( 1 );

	public static final Integer SHRINKING = Integer.valueOf( -1 );

	public static final Integer STATIC = Integer.valueOf( 0 );

	public static final List< String > FEATURES = new ArrayList<>( 3 );

	public static final Map< String, String > FEATURE_NAMES = new HashMap<>( 3 );

	public static final Map< String, String > FEATURE_SHORT_NAMES = new HashMap<>( 3 );

	public static final Map< String, Dimension > FEATURE_DIMENSIONS = new HashMap<>( 3 );

	public static final Map< String, Boolean > IS_INT = new HashMap<>( 2 );

	static
	{
		FEATURES.add( GROWTH_PHASE );
		FEATURES.add( GROWTH );
		FEATURES.add( GROWTH_ANGLE );

		FEATURE_NAMES.put( GROWTH_PHASE, "Growth phase" );
		FEATURE_NAMES.put( GROWTH, "Branch growth" );
		FEATURE_NAMES.put( GROWTH_ANGLE, "Growth angle" );

		FEATURE_SHORT_NAMES.put( GROWTH_PHASE, "Growth phase" );
		FEATURE_SHORT_NAMES.put( GROWTH, "Branch growth" );
		FEATURE_SHORT_NAMES.put( GROWTH_ANGLE, "Growth angle" );

		FEATURE_DIMENSIONS.put( GROWTH_PHASE, Dimension.NONE );
		FEATURE_DIMENSIONS.put( GROWTH, Dimension.LENGTH );
		FEATURE_DIMENSIONS.put( GROWTH_ANGLE, Dimension.ANGLE );

		IS_INT.put( GROWTH_PHASE, Boolean.TRUE );
		IS_INT.put( GROWTH, Boolean.FALSE );
		IS_INT.put( GROWTH_ANGLE, Boolean.FALSE );
	}

	private int numThreads;

	private long processingTime;

	/*
	 * CONSTRUCTOR
	 */

	public BranchGrowPhaseAnalyzer()
	{
		setNumThreads();
	}

	@Override
	public void process( final Collection< DefaultWeightedEdge > edges, final Model model )
	{
		if ( edges.isEmpty() )
		{ return; }

		final FeatureModel featureModel = model.getFeatureModel();

		final ArrayBlockingQueue< DefaultWeightedEdge > queue = new ArrayBlockingQueue<>( edges.size(), false, edges );
		final ConcurrentHashMap< Integer, Spot > firstSpotOfTrackMap = new ConcurrentHashMap<>( model.getTrackModel().nTracks( true ) );

		final Thread[] threads = SimpleMultiThreading.newThreads( numThreads );
		for ( int i = 0; i < threads.length; i++ )
		{
			threads[ i ] = new Thread( "BranchGrowPhaseAnalyzer thread " + i )
			{
				@Override
				public void run()
				{
					DefaultWeightedEdge edge;
					while ( ( edge = queue.poll() ) != null )
					{

						/*
						 * Skip junction tracks.
						 */
						final Integer trackID = model.getTrackModel().trackIDOf( edge );
						final Double meanQuality = model.getFeatureModel().getTrackFeature( trackID, TrackSpotQualityFeatureAnalyzer.TRACK_MEAN_QUALITY );
						if ( !meanQuality.equals( SkeletonKeyPointsDetector.END_POINTS_QUALITY_VALUE ) )
							continue;

						final Spot source = model.getTrackModel().getEdgeSource( edge );
						final Spot target = model.getTrackModel().getEdgeTarget( edge );

						/*
						 * Growth phase.
						 */

						final Integer growthPhase;
						final Double netGrowth;
						final Double bls = source.getFeature( BranchLengthAnalyzerFactory.FEATURE );
						final Double blt = target.getFeature( BranchLengthAnalyzerFactory.FEATURE );
						if ( null == bls || null == blt )
						{
							growthPhase = null;
							netGrowth = null;
						}
						else
						{
							netGrowth = blt.doubleValue() - bls.doubleValue();
							// Super pedantic.
							if ( blt.doubleValue() > bls.doubleValue() )
								growthPhase = GROWING;
							else if ( blt.doubleValue() < bls.doubleValue() )
								growthPhase = SHRINKING;
							else
								growthPhase = STATIC;
						}
						featureModel.putEdgeFeature( edge, GROWTH_PHASE, growthPhase.doubleValue() );
						featureModel.putEdgeFeature( edge, GROWTH, netGrowth );

						/*
						 * Growth angle. Defined as the angle between the line
						 * from the first spot of the track to the source spot,
						 * and the line from the source spot and the target
						 * spot. We neglect the Z coordinate.
						 */

						// Find first spot of track.
						final Spot first = firstSpotOfTrackMap.computeIfAbsent( trackID, id -> {
							final TrackModel trackModel = model.getTrackModel();
							final Set< Spot > spots = trackModel.trackSpots( id );
							final List< Spot > track = new ArrayList<>( spots );
							Collections.sort( track, Spot.frameComparator );
							return track.get( 0 );
						} );

						final double dx1 = source.diffTo( first, Spot.POSITION_X );
						final double dy1 = source.diffTo( first, Spot.POSITION_Y );
						final double alpha1 = Math.atan2( dy1, dx1 );
						final double dx2 = target.diffTo( source, Spot.POSITION_X );
						final double dy2 = target.diffTo( source, Spot.POSITION_Y );
						final double alpha2 = Math.atan2( dy2, dx2 );

						final double deltaAngle = Math.atan2( Math.sin( alpha1 - alpha2 ), Math.cos( alpha1 - alpha2 ) );
						featureModel.putEdgeFeature( edge, GROWTH_ANGLE, Math.abs( deltaAngle ) );
					}
				}
			};
		}

		final long start = System.currentTimeMillis();
		SimpleMultiThreading.startAndJoin( threads );
		final long end = System.currentTimeMillis();
		processingTime = end - start;
	}

	@Override
	public boolean isLocal()
	{
		return true;
	}

	@Override
	public String getKey()
	{
		return KEY;
	}

	@Override
	public int getNumThreads()
	{
		return numThreads;
	}

	@Override
	public void setNumThreads()
	{
		this.numThreads = Runtime.getRuntime().availableProcessors();
	}

	@Override
	public void setNumThreads( final int numThreads )
	{
		this.numThreads = numThreads;

	}

	@Override
	public long getProcessingTime()
	{
		return processingTime;
	}

	@Override
	public List< String > getFeatures()
	{
		return FEATURES;
	}

	@Override
	public Map< String, String > getFeatureShortNames()
	{
		return FEATURE_SHORT_NAMES;
	}

	@Override
	public Map< String, String > getFeatureNames()
	{
		return FEATURE_NAMES;
	}

	@Override
	public Map< String, Dimension > getFeatureDimensions()
	{
		return FEATURE_DIMENSIONS;
	}

	@Override
	public String getInfoText()
	{
		return null;
	}

	@Override
	public ImageIcon getIcon()
	{
		return null;
	}

	@Override
	public String getName()
	{
		return KEY;
	}

	@Override
	public Map< String, Boolean > getIsIntFeature()
	{
		return IS_INT;
	}

	@Override
	public boolean isManualFeature()
	{
		return false;
	}
}
