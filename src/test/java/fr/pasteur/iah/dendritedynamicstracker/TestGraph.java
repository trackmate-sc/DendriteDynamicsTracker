package fr.pasteur.iah.dendritedynamicstracker;

import static fiji.plugin.trackmate.gui.Icons.TRACKMATE_ICON;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JFrame;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.detection.ManualDetectorFactory;
import fiji.plugin.trackmate.features.edges.EdgeTargetAnalyzer;
import fiji.plugin.trackmate.features.track.TrackDurationAnalyzer;
import fiji.plugin.trackmate.features.track.TrackIndexAnalyzer;
import fiji.plugin.trackmate.gui.GuiUtils;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings.TrackMateObject;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettingsIO;
import fiji.plugin.trackmate.gui.wizard.TrackMateWizardSequence;
import fiji.plugin.trackmate.gui.wizard.WizardSequence;
import fiji.plugin.trackmate.gui.wizard.descriptors.ConfigureViewsDescriptor;
import fiji.plugin.trackmate.tracking.LAPUtils;
import fiji.plugin.trackmate.tracking.TrackerKeys;
import fiji.plugin.trackmate.tracking.sparselap.SimpleSparseLAPTrackerFactory;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.BranchLengthAnalyzerFactory;
import fr.pasteur.iah.dendritedynamicstracker.trackmate.feature.JunctionIDAnalyzerFactory;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Edge;
import sc.fiji.analyzeSkeleton.Graph;
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.analyzeSkeleton.Vertex;

public class TestGraph
{

	public static void main( final String[] args )
	{
		final String path = "samples/Example3-singleframe.tif";

		ImageJ.main( args );
		final ImagePlus imp = IJ.openImage( path );
		imp.show();

		/*
		 * TrackMate model.
		 */

		final Model model = new Model();
		model.setPhysicalUnits( imp.getCalibration().getUnits(), imp.getCalibration().getTimeUnit() );

		/*
		 * TrackMate settings.
		 */

		final Settings settings = new Settings( imp );
		settings.detectorFactory = new ManualDetectorFactory<>();
		settings.trackerFactory = new SimpleSparseLAPTrackerFactory();
		settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap();
		settings.addSpotAnalyzerFactory( new JunctionIDAnalyzerFactory<>() );
		settings.addSpotAnalyzerFactory( new BranchLengthAnalyzerFactory<>() );
		settings.addEdgeAnalyzer( new EdgeTargetAnalyzer() );
		settings.addTrackAnalyzer( new TrackIndexAnalyzer() );
		settings.addTrackAnalyzer( new TrackDurationAnalyzer() );

		final double maxLinkingDistance = 5.;
		settings.trackerSettings.put( TrackerKeys.KEY_LINKING_MAX_DISTANCE, Double.valueOf( maxLinkingDistance ) );
		settings.trackerSettings.put( TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE, Double.valueOf( maxLinkingDistance ) );

		/*
		 * Analyze skeleton.
		 */

		final int pruneIndex = AnalyzeSkeleton_.SHORTEST_BRANCH;
		final boolean pruneEnds = false; // Don't prune branch ends.
		final boolean shortPath = false; // Don't compute shortest path.
		final boolean silent = true;
		final boolean verbose = false;
		final AnalyzeSkeleton_ skelAnalyzer = new AnalyzeSkeleton_();
		skelAnalyzer.setup( "", imp );
		final SkeletonResult result = skelAnalyzer.run( pruneIndex, pruneEnds, shortPath, imp, silent, verbose, null );

		/*
		 * Parse results.
		 */

		final int frame = 0;
		final SpotCollection spots = model.getSpots();
		final Graph[] graphs = result.getGraph();
		for ( final Graph graph : graphs )
		{
			final List< Vertex > vertices = graph.getVertices();
			final Map< Vertex, Spot > vertexMap = new HashMap<>();

			/*
			 * Find junctions.
			 */

			for ( final Vertex vertex : vertices )
			{
				if ( vertex.getBranches().size() == 1 )
					continue;

				final Spot spot = vertexToSpot( vertex, imp.getCalibration(), frame );
				vertexMap.put( vertex, spot );
				spots.add( spot, Integer.valueOf( frame ) );
			}

			/*
			 * Find end points and link them to their junction.
			 */

			for ( final Vertex vertex : vertices )
			{
				if ( vertex.getBranches().size() != 1 )
					continue;

				final Spot spot = vertexToSpot( vertex, imp.getCalibration(), frame );
				final Edge predecessor = vertex.getBranches().get( 0 );
				if ( null == predecessor )
					continue;

				final Vertex oppositeVertex = predecessor.getOppositeVertex( vertex );
				final Spot junctionSpot = vertexMap.get( oppositeVertex );
				if ( null != junctionSpot )
				{
					spot.setName( "->" + junctionSpot.ID() );
					spot.putFeature( JunctionIDAnalyzerFactory.FEATURE, Double.valueOf( junctionSpot.ID() ) );
					final double branchLength = predecessor.getLength_ra();
					spot.putFeature( BranchLengthAnalyzerFactory.FEATURE, Double.valueOf( branchLength ) );

				}
				else
				{
					spot.setName( "No junction" );
				}
				spots.add( spot, Integer.valueOf( frame ) );
			}

		}
		spots.setVisible( true );

		/*
		 * Compute stuff.
		 */

		final TrackMate trackmate = new TrackMate( model, settings );
		if ( !trackmate.checkInput() || !trackmate.execTracking() )
		{
			IJ.error( "Problem with tracking.", trackmate.getErrorMessage() );
			return;
		}
		trackmate.computeSpotFeatures( false );
		trackmate.computeEdgeFeatures( false );
		trackmate.computeTrackFeatures( false );

		/*
		 * Show results.
		 */

		final DisplaySettings ds = DisplaySettingsIO.readUserDefault();
		ds.setSpotColorBy( TrackMateObject.SPOTS, BranchLengthAnalyzerFactory.FEATURE );

		final SelectionModel selectionModel = new SelectionModel( model );

		final HyperStackDisplayer displayer = new HyperStackDisplayer( model, selectionModel, imp, ds );
		displayer.render();

		// Wizard.
		final WizardSequence sequence = new TrackMateWizardSequence( trackmate, selectionModel, ds );
		final JFrame frame2 = sequence.run( "TrackMate on " + imp.getShortTitle() );
		sequence.setCurrent( ConfigureViewsDescriptor.KEY );
		frame2.setIconImage( TRACKMATE_ICON.getImage() );
		GuiUtils.positionWindow( frame2, imp.getWindow() );
		frame2.setVisible( true );
	}

	private static final Spot vertexToSpot( final Vertex vertex, final Calibration calibration, final int frame )
	{
		final List< Point > points = vertex.getPoints();
		final double xi = points.stream()
				.mapToDouble( p -> p.x )
				.average()
				.getAsDouble();
		final double yi = points.stream()
				.mapToDouble( p -> p.y )
				.average()
				.getAsDouble();
		final double zi = points.stream()
				.mapToDouble( p -> p.z )
				.average()
				.getAsDouble();

		final boolean isJunction = vertex.getBranches().size() > 1;

		final double x = xi * calibration.pixelWidth;
		final double y = yi * calibration.pixelHeight;
		final double z = zi * calibration.pixelDepth;
		final double radius = 0.5;
		final double quality = isJunction ? 2. : 1.;
		final Spot spot = new Spot( x, y, z, radius, quality );
		spot.putFeature( Spot.POSITION_T, Double.valueOf( frame ) );
		return spot;
	}
}
