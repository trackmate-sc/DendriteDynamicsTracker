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
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.util.TMUtils;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.BranchLengthAnalyzerFactory;
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

		final String trackStatFile = determineStatFileName( saveFolder, trackmate.getSettings().imp );
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

			final String[] line = new String[ trackFeatures.size() + 2 ];
			for ( final Integer trackID : trackIDs )
			{
				line[ 0 ] = "" + trackID.intValue();
				line[ 1 ] = trackmate.getModel().getTrackModel().name( trackID );
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
							line[ i + 2 ] = "" + val.intValue();
						else
							line[ i + 2 ] = "" + val.doubleValue();
					}
				}
				csvWriter.writeNext( line );
			}
		}
		catch ( final IOException e )
		{
			errorMessage = "Could not write to " + trackStatFile + "\n";
			errorMessage += e.getMessage();
			return false;
		}

		/*
		 * Save individual branch length.
		 */

		final int nDigits = getNDigits( trackIDs );
		final TrackModel trackModel = trackmate.getModel().getTrackModel();
		for ( final Integer trackID : trackIDs )
		{
			final String branchFile = determineBranchFileName( saveFolder, trackmate.getSettings().imp, trackID, nDigits );
			try (
					Writer writer = Files.newBufferedWriter( Paths.get( branchFile ) );

					CSVWriter csvWriter = new CSVWriter( writer,
							CSVWriter.DEFAULT_SEPARATOR,
							CSVWriter.NO_QUOTE_CHARACTER,
							CSVWriter.DEFAULT_ESCAPE_CHARACTER,
							CSVWriter.DEFAULT_LINE_END );)
			{

				final String[] header1 = new String[ 2 ];
				final String[] header2 = new String[ 2 ];
				header1[ 0 ] = "Time";
				header2[ 0 ] = "(" + TMUtils.getUnitsFor( Dimension.TIME, spaceUnits, timeUnits ) + ")";
				header1[ 1 ] = "BranchLength";
				header2[ 1 ] = "(" + TMUtils.getUnitsFor( Dimension.LENGTH, spaceUnits, timeUnits ) + ")";
				csvWriter.writeNext( header1 );
				csvWriter.writeNext( header2 );

				final List< Spot > branch = new ArrayList<>( trackModel.trackSpots( trackID ) );
				branch.sort( Spot.frameComparator );
				final String[] line = new String[ 2 ];
				for ( final Spot spot : branch )
				{
					line[ 0 ] = spot.getFeature( Spot.POSITION_T ).toString();
					line[ 1 ] = spot.getFeature( BranchLengthAnalyzerFactory.FEATURE ).toString();
					csvWriter.writeNext( line );
				}
			}
			catch ( final IOException e )
			{
				errorMessage = "Could not write to " + branchFile + "\n";
				errorMessage += e.getMessage();
				return false;
			}
		}

		return true;
	}

	private String determineBranchFileName( final File saveFolder, final ImagePlus imp, final int id, final int nDigits )
	{

		final String suffix = String.format( "Dendrite_%0" + nDigits + "d.csv", id );
		if ( null == imp )
			return new File( saveFolder, suffix ).getAbsolutePath();

		if ( null == imp.getOriginalFileInfo()
				|| null == imp.getOriginalFileInfo().fileName
				|| imp.getOriginalFileInfo().fileName.isEmpty() )
		{
			final String title = removeExtension( imp.getTitle() );
			if ( null != title )
				return new File( saveFolder, title + "_" + suffix ).getAbsolutePath();

			return new File( saveFolder, suffix ).getAbsolutePath();
		}

		final String target = removeExtension( imp.getOriginalFileInfo().fileName );
		return new File( saveFolder, target + "_" + suffix ).getAbsolutePath();
	}

	private String determineStatFileName( final File saveFolder, final ImagePlus imp )
	{
		if ( null == imp )
			return new File( saveFolder, "DendriteTracksStatistics.csv" ).getAbsolutePath();

		if ( null == imp.getOriginalFileInfo()
				|| null == imp.getOriginalFileInfo().fileName
				|| imp.getOriginalFileInfo().fileName.isEmpty() )
		{
			final String title = removeExtension( imp.getTitle() );
			if ( null != title )
				return new File( saveFolder, title + "_DendriteTracksStatistics.csv" ).getAbsolutePath();

			return new File( saveFolder, "DendriteTracksStatistics.csv" ).getAbsolutePath();
		}

		final String target = removeExtension( imp.getOriginalFileInfo().fileName );
		return new File( saveFolder, target + "_DendriteTracksStatistics.csv" ).getAbsolutePath();
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

	private static final String removeExtension( String filename )
	{
		if ( filename.indexOf( "." ) > 0 )
			filename = filename.substring( 0, filename.lastIndexOf( "." ) );

		return filename;
	}

	/**
	 * Computes the N digits needed to represent a collection of Integers.
	 *
	 * @param integers
	 *            the integer collection.
	 * @return the number of digits needed to represent them.
	 */
	private static final int getNDigits( final Set< Integer > integers )
	{
		int max = -1;
		for ( final Integer integer : integers )
			if ( integer > max )
				max = integer;

		return String.valueOf( Math.abs( max ) ).length();
	}

}
