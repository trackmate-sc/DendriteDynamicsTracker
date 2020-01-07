package fr.pasteur.iah.dendritedynamicstracker;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.opencsv.CSVWriter;

import fiji.plugin.trackmate.Dimension;
import fiji.plugin.trackmate.FeatureModel;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.util.TMUtils;
import ij.ImagePlus;
import net.imglib2.algorithm.Algorithm;

/**
 * Exports dendrite tracking data to CSV files.
 *
 * @author Jean-Yves Tinevez
 *
 */
public class DendriteDynamicsCSVExporter implements Algorithm
{

	private String errorMessage;

	private final TrackMate trackmate;

	public DendriteDynamicsCSVExporter( final TrackMate trackmate )
	{
		this.trackmate = trackmate;
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
		 * Determine where to save the CSV files.
		 */

		final String saveFolderStr = determineSaveFolder( trackmate.getSettings().imp );
		final File saveFolder = new File( saveFolderStr );
		if ( !saveFolder.canWrite() )
		{
			errorMessage = "Cannot write to save folder: " + saveFolder;
			return false;
		}

		/*
		 * Save track statistics.
		 */

		final String spaceUnits = trackmate.getModel().getSpaceUnits();
		final String timeUnits = trackmate.getModel().getTimeUnits();
		final FeatureModel fm = trackmate.getModel().getFeatureModel();
		final List< String > trackFeatures = new ArrayList<>( fm.getTrackFeatures() );
		final Set< Integer > trackIDs = trackmate.getModel().getTrackModel().trackIDs( true );

		final String trackStatFile = new File( saveFolder, "DendriteTracksStatistics.csv" ).getAbsolutePath();
		try (
				Writer writer = Files.newBufferedWriter( Paths.get( trackStatFile ) );

				CSVWriter csvWriter = new CSVWriter( writer,
						CSVWriter.DEFAULT_SEPARATOR,
						CSVWriter.NO_QUOTE_CHARACTER,
						CSVWriter.DEFAULT_ESCAPE_CHARACTER,
						CSVWriter.DEFAULT_LINE_END );)
		{

			final String[] header1 = new String[ trackFeatures.size() + 2 ];
			final String[] header2 = new String[ trackFeatures.size() + 2 ];
			header1[ 0 ] = "TrackID";
			header2[ 0 ] = "";
			header1[ 1 ] = "Name";
			header2[ 1 ] = "";
			for ( int i = 0; i < trackFeatures.size(); i++ )
			{
				final String feature = trackFeatures.get( i );
				final Dimension dimension = fm.getTrackFeatureDimensions().get( feature );
				header1[ i + 2 ] = feature;
				header2[ i + 2 ] = "(" + TMUtils.getUnitsFor( dimension, spaceUnits, timeUnits ) + ")";
			}
			csvWriter.writeNext( header1 );
			csvWriter.writeNext( header2 );

			for ( final Integer trackID : trackIDs )
			{
				final String[] line = new String[ trackFeatures.size() + 2 ];
				line[ 0 ] = trackmate.getModel().getTrackModel().name( trackID );
				line[ 1 ] = "" + trackID.intValue();
				for ( int i = 0; i < trackFeatures.size(); i++ )
				{
					final String feature = trackFeatures.get( i );
					final Double val = fm.getTrackFeature( trackID, feature );
					if ( null == val )
					{
						line[ i + 2 ] = "None";
					}
					else
					{
						if ( fm.getTrackFeatureIsInt().get( feature ).booleanValue() )
						{
							line[ i + 2 ] = "" + val.intValue();
						}
						else
						{
							line[ i + 2 ] = "" + val.doubleValue();
						}
					}
				}
			}
		}
		catch ( final IOException e )
		{
			errorMessage = "Could not write to " + trackStatFile + "\n";
			errorMessage += e.getMessage();
			return false;
		}

		return true;
	}

	/**
	 * Returns a target folder where to save some data, based on the specified
	 * {@link ImagePlus}. If file info can be retrieved from it, we use it to
	 * return a save folder. In any other use case we return the user home
	 * directory.
	 *
	 * @param imp
	 *            the {@link ImagePlus}.
	 * @return a target folder for saving CSV files.
	 */
	private String determineSaveFolder( final ImagePlus imp )
	{
		final String userHome = System.getProperty( "user.home" );
		if ( null == imp
				|| null == imp.getOriginalFileInfo()
				|| null == imp.getOriginalFileInfo().directory
				|| imp.getOriginalFileInfo().directory.isEmpty() )
			return userHome;

		final String target = imp.getOriginalFileInfo().directory;
		final File targetFile = new File( target );
		if ( !targetFile.canWrite() )
			return userHome;

		return target;
	}

	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}

}
