package fr.pasteur.iah.dendritedynamicstracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Spot;
import net.imglib2.Interval;

public class DendriteTrackFilter
{

	private DendriteTrackFilter()
	{}

	/**
	 * Removes the single spots and tracks that have at least one spot touching
	 * the border of the specified interval or are outside of it.
	 * <p>
	 * Visible and invisible tracks and spots are considered
	 * 
	 * @param model
	 *            the model to query the tracks and spots from.
	 * @param roi
	 *            the interval to test for contact.
	 * @param calibration
	 *            the physical pixel size to convert spot position to pixel
	 *            coordinates.
	 */
	public static void pruneBorderTracks( final Model model, final Interval roi, final double[] calibration )
	{
		/*
		 * Deal with tracks.
		 */

		final Collection< Integer > trackIDsToRemove = new ArrayList<>();
		final int nDims = roi.numDimensions();

		NEXT_TRACK: for ( final Integer trackID : model.getTrackModel().trackIDs( false ) )
		{
			final Set< Spot > spots = model.getTrackModel().trackSpots( trackID );
			for ( final Spot spot : spots )
			{
				for ( int d = 0; d < nDims; d++ )
				{
					final double pos = spot.getDoublePosition( d );
					final long pixel = Math.round( pos / calibration[ d ] );
					if ( pixel <= roi.min( d ) || pixel >= roi.max( d ) )
					{
						trackIDsToRemove.add( trackID );
						continue NEXT_TRACK;
					}
				}
			}
		}

		model.beginUpdate();
		try
		{
			for ( final Integer trackID : trackIDsToRemove )
				for ( final Spot spot : model.getTrackModel().trackSpots( trackID ) )
					model.removeSpot( spot );
		}
		finally
		{
			model.endUpdate();
		}

		/*
		 * Deal with lonely spots.
		 */

		final Collection< Spot > spotsToRemove = new ArrayList<>();
		for ( final Spot spot : model.getSpots().iterable( false ) )
		{
			for ( int d = 0; d < nDims; d++ )
			{
				final double pos = spot.getDoublePosition( d );
				final long pixel = Math.round( pos / calibration[ d ] );
				if ( pixel <= roi.min( d ) || pixel >= roi.max( d ) )
					spotsToRemove.add( spot );
			}
		}

		model.beginUpdate();
		try
		{
			for ( final Spot spot : spotsToRemove )
				model.removeSpot( spot );
		}
		finally
		{
			model.endUpdate();
		}
	}
}
