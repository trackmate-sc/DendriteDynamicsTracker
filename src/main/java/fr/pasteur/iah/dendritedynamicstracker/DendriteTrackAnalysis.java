package fr.pasteur.iah.dendritedynamicstracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.data.xy.DefaultXYDataset;

import fiji.plugin.trackmate.FeatureModel;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.features.track.TrackSpotQualityFeatureAnalyzer;
import fr.pasteur.iah.dendritedynamicstracker.SkeletonKeyPointsDetector.DetectionResults;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.BranchGrowPhaseAnalyzer;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.BranchLengthAnalyzerFactory;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.DendriteTrackNIncorrectIDs;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.JunctionIDAnalyzerFactory;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.TotalBranchTravelAnalyzer;
import net.imglib2.algorithm.Algorithm;
import sc.fiji.analyzeSkeleton.Edge;
import sc.fiji.analyzeSkeleton.Vertex;

public class DendriteTrackAnalysis implements Algorithm
{

	private static final boolean DO_PATCH = true;

	private final TrackMate endPointTrackMate;

	private final Model junctionModel;

	private final DetectionResults detectionResults;

	private String errorMessage;

	public DendriteTrackAnalysis(
			final TrackMate endPointTrackmate,
			final Model junctionModel,
			final DetectionResults detectionResults )
	{
		this.endPointTrackMate = endPointTrackmate;
		this.junctionModel = junctionModel;
		this.detectionResults = detectionResults;
	}

	@Override
	public boolean process()
	{
		final Model model = endPointTrackMate.getModel();
		final TrackModel trackModel = model.getTrackModel();
		final FeatureModel featureModel = model.getFeatureModel();
		final Set< Integer > trackIDs = trackModel.trackIDs( true );
		for ( final Integer trackID : trackIDs )
		{
			/*
			 * Differentiate between junction tracks and end-point tracks based
			 * on their quality, in case the user merged both track types.
			 */
			final Double meanQuality = featureModel.getTrackFeature( trackID, TrackSpotQualityFeatureAnalyzer.TRACK_MEAN_QUALITY );
			if ( !meanQuality.equals( SkeletonKeyPointsDetector.END_POINTS_QUALITY_VALUE ) )
				continue;

			/*
			 * Try to 'patch' branch tracks: Can we find again the junction they
			 * branch from, despite gaps, missed detection and other branches
			 * that stems from the branch?
			 */
			patchTrack( trackID );
		}

		/*
		 * Re-compute the features for the branches features now.
		 */

		endPointTrackMate.getSettings().addEdgeAnalyzer( new BranchGrowPhaseAnalyzer() );
		endPointTrackMate.getSettings().addTrackAnalyzer( new TotalBranchTravelAnalyzer() );
		endPointTrackMate.computeTrackFeatures( true );
		endPointTrackMate.computeEdgeFeatures( true );

		/*
		 * Massage and export analysis results.
		 */

		exportAnalysis();


		return true;
	}

	private void exportAnalysis()
	{
		// TODO Auto-generated method stub

	}

	/**
	 * Try to 'patch' branch tracks: Can we find again the junction they branch
	 * from, despite gaps, missed detection and other branches that stems from
	 * the branch?
	 */
	private void patchTrack( final Integer trackID )
	{
		final TrackModel trackModel = endPointTrackMate.getModel().getTrackModel();
		final List< Spot > spots = new ArrayList<>( trackModel.trackSpots( trackID ) );
		spots.sort( Spot.frameComparator );

		/*
		 * Collect raw branch length and junction ID.
		 */

		final double[] branchLength = new double[ spots.size() ];
		final double[] junctionIDs = new double[ spots.size() ];
		final double[] time = new double[ spots.size() ];

		for ( int i = 0; i < spots.size(); i++ )
		{
			final Spot spot = spots.get( i );
			time[ i ] = spot.getFeature( Spot.POSITION_T );

			final Spot junction = detectionResults.junctionMap.get( spot );
			final Integer junctionTrackID = junctionModel.getTrackModel().trackIDOf( junction );
			junctionIDs[ i ] = ( junctionTrackID != null )
					? junctionTrackID.doubleValue()
					: Double.NaN;

			final Vertex vertex = detectionResults.getVertexFor( spot );
			if ( vertex == null )
				continue;

			final Edge predecessor = vertex.getBranches().get( 0 );
			if ( null == predecessor )
				continue;

			final double length = predecessor.getLength_ra();
			branchLength[ i ] = length;
		}

		final double[] bestBranchLength;
		final double[] bestJunctionIDs;
		final int nUncorrectedIDJumps;

		/*
		 * How many unique Junction IDs do we have? If we have more than 1, it
		 * means that the end-point "jumped" (incorrect tracking) or that the
		 * branch forked.
		 */

		final double[] uniqueIDs = Arrays.stream( junctionIDs )
				.filter( v -> !Double.isNaN( v ) )
				.distinct()
				.toArray();
		if ( DO_PATCH &&  uniqueIDs.length > 1 )
		{

			/*
			 * Try to patch the track by retrieving junctions that correspond to
			 * the main junction track of this end-point track.
			 */

			Arrays.sort( uniqueIDs );
			// Histogram of these unique IDs.
			final int[] counts = new int[ uniqueIDs.length ];
			for ( int i = 0; i < junctionIDs.length; i++ )
			{
				final double junctionID = junctionIDs[ i ];
				if ( Double.isNaN( junctionID ) )
					continue;
				final int idx = Arrays.binarySearch( uniqueIDs, junctionID );
				counts[ idx ]++;
			}

			/*
			 * Try to bridge and restore branch length for end-points that do
			 * not connect to the desired junction. We try each junction unique
			 * long id, and only take the one that gives the best correction.
			 */

			// Store the corrected branch length for each candidate.
			final double[][] correctedBranchLength = new double[ uniqueIDs.length ][ branchLength.length ];
			for ( int i = 0; i < uniqueIDs.length; i++ )
				correctedBranchLength[ i ] = Arrays.copyOf( branchLength, branchLength.length );

			// Store the corrected junction ID for each candidate.
			final double[][] correctedJunctionIDs = new double[ uniqueIDs.length ][ junctionIDs.length ];
			for ( int i = 0; i < uniqueIDs.length; i++ )
				correctedJunctionIDs[ i ] = Arrays.copyOf( junctionIDs, junctionIDs.length );

			// Stores the successful correction we have made for each candidate.
			final int[] successfulCorrections = new int[ uniqueIDs.length ];

			/*
			 * Try with each candidate junction ID if we can improve the track.
			 */
			for ( int i = 0; i < uniqueIDs.length; i++ )
			{
				final double candidate = uniqueIDs[ i ];
				for ( int t = 0; t < junctionIDs.length; t++ )
				{
					if ( junctionIDs[ t ] == candidate )
					{
						successfulCorrections[ i ]++;
						continue; // Do not touch.
					}

					/*
					 * Breadth-first iterator to find the candidate junction.
					 * The queue stores the paths so that we can backtrack to
					 * the start of the search.
					 */

					final ArrayDeque< List< Vertex > > queue = new ArrayDeque<>();
					final Set< Vertex > visited = new HashSet<>();

					final Spot endPointSpot = spots.get( t );
					final Vertex endPointVertex = detectionResults.getVertexFor( endPointSpot );
					queue.add( Collections.singletonList( endPointVertex ) );

					while ( !queue.isEmpty() )
					{
						final List< Vertex > path = queue.remove();

						// Take the last item of the path.
						final Vertex vertex = path.get( path.size() - 1 );
						if ( visited.contains( vertex ) )
							continue;

						visited.add( vertex );

						final Spot junctionCandidate = detectionResults.getSpotFor( vertex );
						if ( null != junctionCandidate )
						{
							final Integer junctionCandidateID = junctionModel.getTrackModel().trackIDOf( junctionCandidate );
							if ( null != junctionCandidateID )
							{
								if ( junctionCandidateID.doubleValue() == candidate )
								{
									// Found the right junction ID! Accepting
									// this junction.

									// Compute new branch length.
									double sumBranchLength = 0.;
									Vertex source = path.get( 0 );
									for ( int j = 1; j < path.size(); j++ )
									{
										final Vertex target = path.get( j );
										for ( final Edge edge : target.getBranches() )
										{
											if ( source.equals( edge.getOppositeVertex( target ) ) )
											{
												sumBranchLength += edge.getLength_ra();
												break;
											}
										}

										source = target;
									}

									/*
									 * Store new branch length and new junction
									 * id.
									 */
									correctedBranchLength[ i ][ t ] = sumBranchLength;
									correctedJunctionIDs[ i ][ t ] = candidate;
									successfulCorrections[ i ]++;

									break;
								}
							}
						}

						final ArrayList< Edge > branches = vertex.getBranches();
						for ( final Edge edge : branches )
						{
							final Vertex other = edge.getOppositeVertex( vertex );
							final List< Vertex > newPath = new ArrayList<>( path );
							newPath.add( other );
							queue.add( newPath );
						}
					}
				}
			}

			/*
			 * Now, what candidate gave the best correction?
			 */

			double maxNCorrections = -1.;
			int maxIndex = -1;
			for ( int i = 0; i < successfulCorrections.length; i++ )
			{
				if ( successfulCorrections[ i ] > maxNCorrections )
				{
					maxNCorrections = successfulCorrections[ i ];
					maxIndex = i;
				}
			}

			bestBranchLength = correctedBranchLength[ maxIndex ];
			bestJunctionIDs = correctedJunctionIDs[ maxIndex ];
			nUncorrectedIDJumps = spots.size() - successfulCorrections[ maxIndex ];
		}
		else
		{
			// No need to patch.
			bestBranchLength = branchLength;
			bestJunctionIDs = junctionIDs;
			nUncorrectedIDJumps = 0;
		}

		/*
		 * Store the possibly corrected branch length and junction ID in
		 * TrackMate features.
		 */

		for ( int t = 0; t < spots.size(); t++ )
		{
			final Spot spot = spots.get( t );
			final double bl = bestBranchLength[ t ];
			final double jid = bestJunctionIDs[ t ];
			spot.putFeature( BranchLengthAnalyzerFactory.FEATURE, Double.valueOf( bl ) );
			spot.putFeature( JunctionIDAnalyzerFactory.FEATURE, Double.valueOf( jid ) );
			spot.setName( "->" + (int) jid );
		}
		endPointTrackMate.getModel().getFeatureModel().putTrackFeature(
				trackID, DendriteTrackNIncorrectIDs.FEATURE, Double.valueOf( nUncorrectedIDJumps ) );
	}

	@SuppressWarnings( "unused" )
	private final void plotBranchLength( final double[] time, final double[] branchLength, final double[] junctionIDs, final String name )
	{

		final DefaultXYDataset datasetBranchLength = new DefaultXYDataset();
		datasetBranchLength.addSeries( "Branch length", new double[][] { time, branchLength } );
		final JFreeChart chartBranchLength = ChartFactory.createXYLineChart(
				name,
				"Time (" + endPointTrackMate.getModel().getTimeUnits() + ")",
				"Branch length (" + endPointTrackMate.getModel().getSpaceUnits() + ")",
				datasetBranchLength );
		final ChartPanel chartBranchLengthPanel = new ChartPanel( chartBranchLength );

		final DefaultXYDataset datasetJunctionID = new DefaultXYDataset();
		datasetJunctionID.addSeries( "Junction ID", new double[][] { time, junctionIDs } );
		final JFreeChart chartJunctionID = ChartFactory.createXYLineChart(
				name,
				"Time (" + endPointTrackMate.getModel().getTimeUnits() + ")",
				"Junction ID",
				datasetJunctionID );
		final ChartPanel chartJunctionIDPanel = new ChartPanel( chartJunctionID );

		final JFrame frame = new JFrame( "Corrected branch length for " + name );
		final JPanel panel = new JPanel();
		final BoxLayout layout = new BoxLayout( panel, BoxLayout.PAGE_AXIS );
		panel.setLayout( layout );
		panel.add( chartBranchLengthPanel );
		panel.add( chartJunctionIDPanel );

		frame.getContentPane().add( panel );
		frame.pack();
		frame.setVisible( true );
	}

	@Override
	public boolean checkInput()
	{
		return true;
	}

	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}
}
