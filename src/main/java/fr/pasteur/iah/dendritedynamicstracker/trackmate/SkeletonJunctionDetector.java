package fr.pasteur.iah.dendritedynamicstracker.trackmate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.detection.DetectionUtils;
import fiji.plugin.trackmate.detection.SpotDetector;
import ij.ImagePlus;
import ij.plugin.Duplicator;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Graph;
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.analyzeSkeleton.Vertex;

public class SkeletonJunctionDetector< T extends RealType< T > & NativeType< T > > implements SpotDetector< T >
{

	private final static String BASE_ERROR_MESSAGE = "SkeletonEndPointsDetector: ";

	private static final double JUNCTION_RADIUS = 1.;

	private static final double JUNCTION_QUALITY_VALUE = 2.;

	private final RandomAccessible< T > skeleton;

	private final RandomAccessible< T > origImg;

	private final int prunningMethod;

	private final Interval interval;

	private final double[] calibration;

	/** The list of {@link Spot} that will be populated by this detector. */
	private final List< Spot > spots = new ArrayList<>();

	private String errorMessage;

	private long processingTime;

	public SkeletonJunctionDetector(
			final RandomAccessible< T > skeleton,
			final RandomAccessible< T > origImg,
			final int prunningMethod,
			final Interval interval,
			final double[] calibration )
	{
		this.skeleton = skeleton;
		this.origImg = origImg;
		this.prunningMethod = prunningMethod;
		this.interval = DetectionUtils.squeeze( interval );
		this.calibration = calibration;
	}

	@Override
	public List< Spot > getResult()
	{
		return spots;
	}

	@Override
	public boolean checkInput()
	{
		if ( null == skeleton )
		{
			errorMessage = BASE_ERROR_MESSAGE + "Image is null.";
			return false;
		}
		if ( skeleton.numDimensions() > 3 || skeleton.numDimensions() < 2 )
		{
			errorMessage = BASE_ERROR_MESSAGE + "Skeleton image must be 2D or 3D, got " + skeleton.numDimensions() + "D.";
			return false;
		}
		return true;
	}

	@Override
	public boolean process()
	{
		final long start = System.currentTimeMillis();
		spots.clear();

		final AnalyzeSkeleton_ skelAnalyzer = new AnalyzeSkeleton_();

		final boolean pruneEnds = false; // Don't prune branch ends.
		final boolean shortPath = false; // Don't compute shortest path.
		final boolean silent = true;
		final boolean verbose = false;

		// Skeleton.
		final IntervalView< T > skeletonIntervalView = Views.interval( skeleton, interval );
		final IntervalView< T > skeletonCrop = Views.zeroMin( skeletonIntervalView );
		final ImagePlus skeletonImp = ImageJFunctions.wrap( skeletonCrop, "Skeleton input for AnalyseSkeleton" );
		final ImagePlus skeletonFrame = new Duplicator().run( skeletonImp );

		// Original data (for cycle prunning).
		final IntervalView< T > origDataIntervalView = Views.interval( origImg, interval );
		final IntervalView< T > origDataCrop = Views.zeroMin( origDataIntervalView );
		final ImagePlus origDataImp = ImageJFunctions.wrap( origDataCrop, "Original data input for AnalyseSkeleton" );
		final ImagePlus origDataFrame = new Duplicator().run( origDataImp );

		skelAnalyzer.setup( "", skeletonFrame );
		final SkeletonResult result = skelAnalyzer.run( prunningMethod, pruneEnds, shortPath, origDataFrame, silent, verbose, null );

		final long startx = interval.numDimensions() > 0 ? interval.min( 0 ) : 0;
		final long starty = interval.numDimensions() > 1 ? interval.min( 1 ) : 0;
		final long startz = interval.numDimensions() > 2 ? interval.min( 2 ) : 0;
		final long[] topLeft = new long[] { startx, starty, startz };

		final Graph[] graphs = result.getGraph();
		for ( final Graph graph : graphs )
		{
			final List< Vertex > vertices = graph.getVertices();
			final Map< Vertex, Spot > vertexMap = new HashMap<>();

			for ( final Vertex vertex : vertices )
			{
				if ( vertex.getBranches().size() == 1 )
					continue;

				final Spot spot = vertexToSpot( vertex, calibration, topLeft );
				vertexMap.put( vertex, spot );
				spots.add( spot );
			}
		}

		final long end = System.currentTimeMillis();
		this.processingTime = end - start;

		return true;
	}

	private static final Spot vertexToSpot( final Vertex vertex, final double[] calibration, final long[] start )
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

		final double x = ( start[ 0 ] + xi ) * calibration[ 0 ];
		final double y = ( start[ 1 ] + yi ) * calibration[ 1 ];
		final double z = ( start[ 2 ] + zi ) * calibration[ 2 ];
		final Spot spot = new Spot( x, y, z, JUNCTION_RADIUS, JUNCTION_QUALITY_VALUE );
		return spot;
	}

	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}

	@Override
	public long getProcessingTime()
	{
		return processingTime;
	}
}
