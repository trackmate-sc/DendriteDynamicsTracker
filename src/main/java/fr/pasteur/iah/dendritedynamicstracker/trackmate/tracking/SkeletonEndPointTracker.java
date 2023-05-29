/*-
 * #%L
 * A Fiji plugin to track the dynamics of dendrites in 2D time-lapse movies.
 * %%
 * Copyright (C) 2019 - 2023 Institut Pasteur
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the Institut Pasteur / IAH nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package fr.pasteur.iah.dendritedynamicstracker.trackmate.tracking;

import java.util.Map;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.tracking.TrackerKeys;
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPFrameToFrameTracker;
import fiji.plugin.trackmate.tracking.jaqaman.costfunction.CostFunction;
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
