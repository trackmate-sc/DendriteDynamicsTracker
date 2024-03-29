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
package fr.pasteur.iah.dendritedynamicstracker;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;

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

		final String rootFolderStr = determineRootSaveFolder( trackmate.getSettings().imp );
		final File rootFolder = new File( rootFolderStr );
		if ( !rootFolder.canWrite() )
		{
			errorMessage = "Cannot write to save folder: " + rootFolder;
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

		final String trackStatFile = determineStatFileName( rootFolder, trackmate.getSettings().imp );
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

		final String saveFolderIndividuals = determineIndividualFilesSaveFolder( rootFolder, trackmate.getSettings().imp );
		final int nDigits = getNDigits( trackIDs );
		final TrackModel trackModel = trackmate.getModel().getTrackModel();
		for ( final Integer trackID : trackIDs )
		{
			final String branchFile = determineBranchFileName( new File( saveFolderIndividuals ), trackmate.getSettings().imp, trackID, nDigits );
			try (
					Writer writer = Files.newBufferedWriter( Paths.get( branchFile ) );

					CSVWriter csvWriter = new CSVWriter( writer,
							CSVWriter.DEFAULT_SEPARATOR,
							CSVWriter.NO_QUOTE_CHARACTER,
							CSVWriter.DEFAULT_ESCAPE_CHARACTER,
							CSVWriter.DEFAULT_LINE_END );)
			{

				final String[] header1 = new String[ 3 ];
				final String[] header2 = new String[ 3 ];
				header1[ 0 ] = "Time";
				header2[ 0 ] = "(" + TMUtils.getUnitsFor( Dimension.TIME, spaceUnits, timeUnits ) + ")";
				header1[ 1 ] = "BranchLength";
				header2[ 1 ] = "(" + TMUtils.getUnitsFor( Dimension.LENGTH, spaceUnits, timeUnits ) + ")";
				header1[ 2 ] = "BranchSpeed";
				header2[ 2 ] = "(" + TMUtils.getUnitsFor( Dimension.VELOCITY, spaceUnits, timeUnits ) + ")";
				csvWriter.writeNext( header1 );
				csvWriter.writeNext( header2 );

				final List< Spot > branch = new ArrayList<>( trackModel.trackSpots( trackID ) );
				branch.sort( Spot.frameComparator );

				// Used to compute branch velocity.
				double previousTime = branch.get( 0 ).getFeature( Spot.POSITION_T ) - trackmate.getSettings().dt;
				double previousLength = 0.;

				// Content of the line in the CSV file.
				final String[] line = new String[ 3 ];

				// Write pre-birth of the branch as first line.
				line[ 0 ] = Double.valueOf( previousTime ).toString();
				line[ 1 ] = Double.valueOf( previousLength ).toString();
				line[ 2 ] = Double.valueOf( 0. ).toString();
				csvWriter.writeNext( line );

				for ( final Spot spot : branch )
				{
					// Current time.
					final Double currentTime = spot.getFeature( Spot.POSITION_T );
					line[ 0 ] = currentTime.toString();

					// Current branch length.
					final Double currentLength = spot.getFeature( BranchLengthAnalyzerFactory.FEATURE );
					line[ 1 ] = currentLength.toString();

					// Branch velocity
					final double dl = currentLength.doubleValue() - previousLength;
					final double dt = currentTime.doubleValue() - previousTime;
					final Double currentVelocity = Double.valueOf( dl / dt );
					line[ 2 ] = currentVelocity.toString();
					previousLength = currentLength.doubleValue();
					previousTime = currentTime.doubleValue();

					// Write.
					csvWriter.writeNext( line );
				}

				// Write disappearance of the branch as last line.
				line[ 0 ] = Double.valueOf( previousTime + trackmate.getSettings().dt ).toString();
				line[ 1 ] = Double.valueOf( 0. ).toString();
				line[ 2 ] = Double.valueOf( -previousLength / trackmate.getSettings().dt ).toString();
				csvWriter.writeNext( line );

			}
			catch ( final IOException e )
			{
				errorMessage = "Could not write to " + branchFile + "\n";
				errorMessage += e.getMessage();
				return false;
			}
		}

		/*
		 * Save frame by frame statistics.
		 */

		final int nFrames = trackmate.getSettings().nframes;

		// Frame array.
		final int[] frames = new int[ nFrames ];
		for ( int t = 0; t < frames.length; t++ )
			frames[ t ] = t;

		// Time array.
		final double[] times = new double[ nFrames ];
		for ( int t = 0; t < times.length; t++ )
			times[ t ] = frames[ t ] * trackmate.getSettings().dt;

		// Branches birth and death.
		final int[] branchAdditions = new int[ nFrames ];
		final int[] branchDeletions = new int[ nFrames ];

		// First we add information from tracks (nSpots >= 2).
		for ( final Integer trackID : trackModel.trackIDs( true ) )
		{

			final List< Spot > branch = new ArrayList<>( trackModel.trackSpots( trackID ) );
			branch.sort( Spot.frameComparator );

			final Spot firstSpot = branch.get( 0 );
			final int birthFrame = firstSpot.getFeature( Spot.FRAME ).intValue();
			branchAdditions[ birthFrame ]++;

			final Spot lastSpot = branch.get( branch.size() - 1 );
			final int deathFrame = lastSpot.getFeature( Spot.FRAME ).intValue();
			if ( deathFrame < nFrames )
				branchDeletions[ deathFrame ]++;

		}

		// Second we deal with lonely spots, that do not belong in a track.
		for ( final Spot spot : trackmate.getModel().getSpots().iterable( true ) )
		{
			// Skip if it belongs in a track.
			if ( null != trackModel.trackIDOf( spot ) )
				continue;

			final int birthFrame = spot.getFeature( Spot.FRAME ).intValue();
			branchAdditions[ birthFrame ]++;

			final int deathFrame = spot.getFeature( Spot.FRAME ).intValue() + 1;
			if ( deathFrame < nFrames )
				branchDeletions[ deathFrame ]++;
		}

		// Branches alive.
		final int[] nBranch = new int[ nFrames ];
		nBranch[ 0 ] = branchAdditions[ 0 ] - branchDeletions[ 0 ];
		for ( int t = 1; t < nBranch.length; t++ )
			nBranch[ t ] = nBranch[ t - 1 ] + branchAdditions[ t ] - branchDeletions[ t ];

		/*
		 * Get total branch length at frame t: Sum for all spots at that frame.
		 */

		final double[] totalBranchLength = new double[ nFrames ];
		for ( int frame = 0; frame < nFrames; frame++ )
		{
			double tb = 0.;
			for ( final Spot spot : trackmate.getModel().getSpots().iterable( Integer.valueOf( frame ), true ) )
			{
				final Double branchLength = spot.getFeature( BranchLengthAnalyzerFactory.FEATURE );
				if ( null != branchLength && !branchLength.isNaN() )
					tb += branchLength.doubleValue();
			}
			totalBranchLength[ frame ] = tb;
		}

		// Write all of this.
		final String frameStatFile = determineFrameFileName( rootFolder, trackmate.getSettings().imp );
		try (
				Writer writer = Files.newBufferedWriter( Paths.get( frameStatFile ) );

				CSVWriter csvWriter = new CSVWriter( writer,
						CSVWriter.DEFAULT_SEPARATOR,
						CSVWriter.NO_QUOTE_CHARACTER,
						CSVWriter.DEFAULT_ESCAPE_CHARACTER,
						CSVWriter.DEFAULT_LINE_END );)
		{

			final String[] header1 = new String[ 5 ];
			final String[] header2 = new String[ 5 ];
			header1[ 0 ] = "Frame";
			header2[ 1 ] = "";
			header1[ 1 ] = "Time";
			header2[ 1 ] = "(" + TMUtils.getUnitsFor( Dimension.TIME, spaceUnits, timeUnits ) + ")";
			header1[ 2 ] = "BranchAdditions";
			header2[ 2 ] = "";
			header1[ 3 ] = "BranchDeletions";
			header2[ 3 ] = "";
			header1[ 4 ] = "BranchesAlive";
			header2[ 4 ] = "";
			header1[ 4 ] = "TotalBranchLength";
			header2[ 4 ] = "(" + TMUtils.getUnitsFor( Dimension.LENGTH, spaceUnits, timeUnits ) + ")";
			csvWriter.writeNext( header1 );
			csvWriter.writeNext( header2 );

			// Content of the line in the CSV file.
			final String[] line = new String[ 5 ];
			for ( int t = 0; t < nFrames; t++ )
			{
				line[ 0 ] = Integer.valueOf( frames[ t ] ).toString();
				line[ 1 ] = Double.valueOf( times[ t ] ).toString();
				line[ 2 ] = Integer.valueOf( branchAdditions[ t ] ).toString();
				line[ 3 ] = Integer.valueOf( branchDeletions[ t ] ).toString();
				line[ 4 ] = Integer.valueOf( nBranch[ t ] ).toString();
				line[ 4 ] = Double.valueOf( totalBranchLength[ t ] ).toString();
				csvWriter.writeNext( line );
			}
		}
		catch ( final IOException e )
		{
			errorMessage = "Could not write to " + frameStatFile + "\n";
			errorMessage += e.getMessage();
			return false;
		}

		return true;
	}

	private String determineFrameFileName( final File saveFolder, final ImagePlus imp )
	{
		if ( null == imp )
			return new File( saveFolder, "DendriteDynamicsStatistics.csv" ).getAbsolutePath();

		if ( null == imp.getOriginalFileInfo()
				|| null == imp.getOriginalFileInfo().fileName
				|| imp.getOriginalFileInfo().fileName.isEmpty() )
		{
			final String title = removeExtension( imp.getTitle() );
			if ( null != title )
				return new File( saveFolder, title + "_DendriteDynamicsStatistics.csv" ).getAbsolutePath();

			return new File( saveFolder, "DendriteDynamicsStatistics.csv" ).getAbsolutePath();
		}

		final String target = removeExtension( imp.getOriginalFileInfo().fileName );
		return new File( saveFolder, target + "_DendriteDynamicsStatistics.csv" ).getAbsolutePath();
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
	private String determineRootSaveFolder( final ImagePlus imp )
	{
		final String userHome = System.getProperty( "user.home" );
		if ( null == imp
				|| null == imp.getOriginalFileInfo()
				|| null == imp.getOriginalFileInfo().directory
				|| imp.getOriginalFileInfo().directory.isEmpty() )
			return userHome;

		final String root = imp.getOriginalFileInfo().directory;
		final File rootFolder = new File( root );
		if ( !rootFolder.canWrite() )
			return userHome;

		return root;
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
	private String determineIndividualFilesSaveFolder( final File rootFolder, final ImagePlus imp )
	{
		final String userHome = System.getProperty( "user.home" );
		if ( !rootFolder.canWrite() )
		{
			final File targetFolder = new File( userHome, imp.getShortTitle() );
			if ( !targetFolder.exists() )
			{
				final boolean created = targetFolder.mkdir();
				if ( !created )
					return userHome;
			}
			return targetFolder.getAbsolutePath();
		}

		final String fileNameStr = FilenameUtils.removeExtension( imp.getOriginalFileInfo().fileName );
		final File targetFolder = new File( rootFolder, fileNameStr );
		if ( !targetFolder.exists() )
		{
			final boolean created = targetFolder.mkdir();
			if ( !created )
				return rootFolder.getAbsolutePath();
		}

		// Remove all CSV in it.
		Arrays.stream( targetFolder.listFiles( ( f, p ) -> p.toLowerCase().endsWith( "csv" ) ) ).forEach( File::delete );

		return targetFolder.getAbsolutePath();
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
