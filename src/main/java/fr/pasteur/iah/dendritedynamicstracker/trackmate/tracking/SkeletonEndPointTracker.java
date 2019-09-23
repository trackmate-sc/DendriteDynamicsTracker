package fr.pasteur.iah.dendritedynamicstracker.trackmate.tracking;

import java.util.Map;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.tracking.TrackerKeys;
import fiji.plugin.trackmate.tracking.sparselap.SparseLAPFrameToFrameTracker;
import fiji.plugin.trackmate.tracking.sparselap.costfunction.CostFunction;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.JunctionIDAnalyzerFactory;

public class SkeletonEndPointTracker extends SparseLAPFrameToFrameTracker
{

	public SkeletonEndPointTracker( final SpotCollection endPointSpots, final Map< String, Object > settings )
	{
		super( endPointSpots, settings );
	}

	@Override
	protected CostFunction< Spot, Spot > getCostFunction( final Map< String, Double > featurePenalties )
	{
		return new MyCostFunction( ( ( Number ) settings.get( TrackerKeys.KEY_LINKING_MAX_DISTANCE ) ).doubleValue() );
	}

	/**
	 * If the candidate target spot has the same junction track ID, then we
	 * divide the cost by a large factor.
	 */
	private static final class MyCostFunction implements CostFunction< Spot, Spot >
	{

		private static final double FUDGE_FACTOR = 10.;

		private final double maxLinkingDistance;

		public MyCostFunction( final double maxLinkingDistance )
		{
			this.maxLinkingDistance = maxLinkingDistance;
		}

		@Override
		public double linkingCost( final Spot source, final Spot target )
		{
			final double sqDist = source.squareDistanceTo( target );
			if (sqDist > maxLinkingDistance * maxLinkingDistance)
				return Double.POSITIVE_INFINITY;

			final Double sourceID = source.getFeature( JunctionIDAnalyzerFactory.FEATURE );
			final Double targetID = target.getFeature( JunctionIDAnalyzerFactory.FEATURE );
			if ( null == sourceID || null == targetID || !sourceID.equals( targetID ) )
				return sqDist;

			return sqDist / FUDGE_FACTOR;
		}
	}
}
