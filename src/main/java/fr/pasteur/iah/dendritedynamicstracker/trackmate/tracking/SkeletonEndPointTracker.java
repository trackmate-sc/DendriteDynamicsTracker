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
		return new MyCostFunction(
				( ( Number ) settings.get( TrackerKeys.KEY_LINKING_MAX_DISTANCE ) ).doubleValue(),
				( ( Number ) settings.get( SkeletonEndPointTrackerFactory.KEY_MATCHED_COST_FACTOR ) ).doubleValue() );
	}

	@Override
	protected boolean checkSettingsValidity( final Map< String, Object > settings, final StringBuilder str )
	{
		return true;
	}

	/**
	 * If the candidate target spot has the same junction track ID, then we
	 * divide the cost by a large factor.
	 */
	private static final class MyCostFunction implements CostFunction< Spot, Spot >
	{

		private final double maxLinkingDistance;

		private final double matchedCostFactor;

		public MyCostFunction( final double maxLinkingDistance, final double matchedCostFactor )
		{
			this.maxLinkingDistance = maxLinkingDistance;
			this.matchedCostFactor = matchedCostFactor;
		}

		@Override
		public double linkingCost( final Spot source, final Spot target )
		{
			final double d2 = source.squareDistanceTo( target );
			if (d2 > maxLinkingDistance * maxLinkingDistance)
				return Double.POSITIVE_INFINITY;

			final double sqDist = ( d2 == 0 ) ? Double.MIN_NORMAL : d2;
			final Double sourceID = source.getFeature( JunctionIDAnalyzerFactory.FEATURE );
			final Double targetID = target.getFeature( JunctionIDAnalyzerFactory.FEATURE );
			if ( null == sourceID || null == targetID || !sourceID.equals( targetID ) )
				return sqDist;

			return sqDist / matchedCostFactor;
		}
	}
}
