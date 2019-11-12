package fr.pasteur.iah.dendritedynamicstracker.trackmate.feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

import javax.swing.ImageIcon;

import fiji.plugin.trackmate.Dimension;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.features.track.TrackAnalyzer;
import fiji.plugin.trackmate.features.track.TrackSpotQualityFeatureAnalyzer;
import fr.pasteur.iah.dendritedynamicstracker.SkeletonKeyPointsDetector;
import net.imglib2.multithreading.SimpleMultiThreading;

@SuppressWarnings( "deprecation" )
public class TotalBranchTravelAnalyzer implements TrackAnalyzer
{

	/*
	 * CONSTANTS
	 */

	public static final String KEY = "Dendrite branch analyzer";

	/**
	 * How much in total a skeleton end-point travels (accumulative distance)
	 * during the whole track. So a branch that extends and retracts multiple
	 * times will have a large accumulative distance even if the net branch
	 * length change is very little.
	 */
	public static final String ACCUMULATIVE_BRANCH_DISTANCE = "ACCUMULATIVE_BRANCH_DISTANCE";

	/**
	 * What is the net-distance a skeleton end-point travels during the whole
	 * track. So a branch that extends and retracts multiple times will have a
	 * small net branch.
	 */
	public static final String NET_BRANCH_DISTANCE = "NET_BRANCH_DISTANCE";

	public static final List< String > FEATURES = new ArrayList<>( 2 );

	public static final Map< String, String > FEATURE_NAMES = new HashMap<>( 2 );

	public static final Map< String, String > FEATURE_SHORT_NAMES = new HashMap<>( 2 );

	public static final Map< String, Dimension > FEATURE_DIMENSIONS = new HashMap<>( 2 );

	public static final Map< String, Boolean > IS_INT = new HashMap<>( 2 );

	static
	{
		FEATURES.add( ACCUMULATIVE_BRANCH_DISTANCE );
		FEATURES.add( NET_BRANCH_DISTANCE );

		FEATURE_NAMES.put( ACCUMULATIVE_BRANCH_DISTANCE, "Accumulative branch distance" );
		FEATURE_NAMES.put( NET_BRANCH_DISTANCE, "Net branch distance" );

		FEATURE_SHORT_NAMES.put( ACCUMULATIVE_BRANCH_DISTANCE, "Acc. branch dist." );
		FEATURE_SHORT_NAMES.put( NET_BRANCH_DISTANCE, "Net branch dist." );

		FEATURE_DIMENSIONS.put( ACCUMULATIVE_BRANCH_DISTANCE, Dimension.LENGTH );
		FEATURE_DIMENSIONS.put( NET_BRANCH_DISTANCE, Dimension.LENGTH );

		IS_INT.put( ACCUMULATIVE_BRANCH_DISTANCE, Boolean.FALSE );
		IS_INT.put( NET_BRANCH_DISTANCE, Boolean.FALSE );
	}

	private int numThreads;

	private long processingTime;

	public TotalBranchTravelAnalyzer()
	{
		setNumThreads();
	}

	@Override
	public void process( final Collection< Integer > trackIDs, final Model model )
	{
		if ( trackIDs.isEmpty() )
		{ return; }

		/*
		 * Determine what are the min and max frame, to know when the analysis
		 * started.
		 */

		int minF = Integer.MAX_VALUE;
		int maxF = Integer.MIN_VALUE;
		for ( final Spot spot : model.getSpots().iterable( true ) )
		{
			final int frame = spot.getFeature( Spot.FRAME ).intValue();
			if ( frame < minF )
				minF = frame;
			if ( frame > maxF )
				maxF = frame;
		}
		final int minFrame = minF;
		final int maxFrame = maxF;

		/*
		 * Compute features for each frame.
		 */

		final ArrayBlockingQueue< Integer > queue = new ArrayBlockingQueue<>( trackIDs.size(), false, trackIDs );

		final Thread[] threads = SimpleMultiThreading.newThreads( numThreads );
		for ( int i = 0; i < threads.length; i++ )
		{
			threads[ i ] = new Thread( "TotalBranchTravelAnalyzer thread " + i )
			{
				@Override
				public void run()
				{
					Integer trackID;
					while ( ( trackID = queue.poll() ) != null )
					{

						/*
						 * Skip junction tracks.
						 */
						final Double meanQuality = model.getFeatureModel().getTrackFeature( trackID, TrackSpotQualityFeatureAnalyzer.TRACK_MEAN_QUALITY );
						if ( !meanQuality.equals( SkeletonKeyPointsDetector.END_POINTS_QUALITY_VALUE ) )
							continue;

						final Set< Spot > spots = model.getTrackModel().trackSpots( trackID );

						/*
						 * Skip tracks with missing branch length.
						 */
						final boolean missingBranchLengthFeature = spots.stream()
								.anyMatch( s -> ( null == s.getFeature( BranchLengthAnalyzerFactory.FEATURE ) ) );
						if (missingBranchLengthFeature)
						{
							model.getFeatureModel().putTrackFeature( trackID, ACCUMULATIVE_BRANCH_DISTANCE, Double.NaN );
							model.getFeatureModel().putTrackFeature( trackID, NET_BRANCH_DISTANCE, Double.NaN );
							continue;
						}

						final List< Spot > track = new ArrayList<>( spots );
						Collections.sort( track, Spot.frameComparator );

						/*
						 * Do we start at 0 or not? Depend on whether the branch
						 * was already there at the beginning of the analysis.
						 */
						final Spot first = track.get( 0 );
						final int firstFrame = first.getFeature( Spot.FRAME ).intValue();

						double previousBranchLength = ( firstFrame != minFrame )
								? 0.
								: first.getFeature( BranchLengthAnalyzerFactory.FEATURE ).doubleValue();

						double accumulativeDistance = 0.;
						for ( final Spot spot : track )
						{
							final double branchLength = spot.getFeature( BranchLengthAnalyzerFactory.FEATURE ).doubleValue();
							final double delta = branchLength - previousBranchLength;
							accumulativeDistance += Math.abs( delta );

							previousBranchLength = branchLength;
						}

						// Do we finish at the end or not? If not we should consider the branch disappear.
						final Spot last = track.get( track.size()-1 );
						final int lastFrame = last.getFeature( Spot.FRAME ).intValue();
						if (lastFrame < maxFrame)
						{
							// Consider it goes to 0.
							accumulativeDistance += last.getFeature( BranchLengthAnalyzerFactory.FEATURE ).doubleValue();
						}
						model.getFeatureModel().putTrackFeature( trackID, ACCUMULATIVE_BRANCH_DISTANCE, Double.valueOf( accumulativeDistance ) );


						/*
						 * Net-distance only depends on the first and last spot.
						 */

						final double netDistance;
						if ( firstFrame == minFrame && lastFrame == maxFrame)
						{
							// Was there at the start, still there at the end.
							netDistance = last.getFeature( BranchLengthAnalyzerFactory.FEATURE ).doubleValue() - first.getFeature( BranchLengthAnalyzerFactory.FEATURE ).doubleValue();
						}
						else if ( firstFrame == minFrame )
						{
							// Was there at the start, but disappeared before the end.
							netDistance =  - first.getFeature( BranchLengthAnalyzerFactory.FEATURE ).doubleValue();
						}
						else if ( lastFrame == maxFrame )
						{
							// Was there at the end, but appeared after the start.
							netDistance =  last.getFeature( BranchLengthAnalyzerFactory.FEATURE ).doubleValue();
						}
						else
						{
							// Appeared and disappeared during the analysis time range.
							netDistance = 0.;
						}
						model.getFeatureModel().putTrackFeature( trackID, NET_BRANCH_DISTANCE, Double.valueOf( netDistance ) );
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
		return Collections.unmodifiableList( FEATURES );
	}

	@Override
	public Map< String, String > getFeatureShortNames()
	{
		return Collections.unmodifiableMap( FEATURE_SHORT_NAMES );
	}

	@Override
	public Map< String, String > getFeatureNames()
	{
		return Collections.unmodifiableMap( FEATURE_NAMES );
	}

	@Override
	public Map< String, Dimension > getFeatureDimensions()
	{
		return Collections.unmodifiableMap( FEATURE_DIMENSIONS );
	}

	@Override
	public Map< String, Boolean > getIsIntFeature()
	{
		return Collections.unmodifiableMap( IS_INT );
	}

	@Override
	public boolean isManualFeature()
	{
		return false;
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
	public String getKey()
	{
		return KEY;
	}

	@Override
	public String getName()
	{
		return KEY;
	}

	@Override
	public boolean isLocal()
	{
		return true;
	}
}
