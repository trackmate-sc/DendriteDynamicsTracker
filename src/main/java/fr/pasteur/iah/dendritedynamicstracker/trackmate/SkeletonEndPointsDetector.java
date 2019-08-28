package fr.pasteur.iah.dendritedynamicstracker.trackmate;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.ml.clustering.Cluster;
import org.apache.commons.math3.ml.clustering.DBSCANClusterer;
import org.apache.commons.math3.ml.clustering.DoublePoint;

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
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;

public class SkeletonEndPointsDetector< T extends RealType< T > & NativeType< T > > implements SpotDetector< T >
{
	private final static String BASE_ERROR_MESSAGE = "SkeletonEndPointsDetector: ";

	final static double END_POINTS_QUALITY_VALUE = 1.;

	final static double JUNCTION_POINTS_QUALITY_VALUE = 2.;

	private final RandomAccessible< T > img;

	private final Interval interval;

	private final double[] calibration;

	/** The list of {@link Spot} that will be populated by this detector. */
	private final List< Spot > spots = new ArrayList<>();

	private String errorMessage;

	private long processingTime;

	public SkeletonEndPointsDetector( final RandomAccessible< T > img, final Interval interval, final double[] calibration )
	{
		this.img = img;
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
		if ( null == img )
		{
			errorMessage = BASE_ERROR_MESSAGE + "Image is null.";
			return false;
		}
		if ( img.numDimensions() > 3 || img.numDimensions() < 2 )
		{
			errorMessage = BASE_ERROR_MESSAGE + "Image must be 2D or 3D, got " + img.numDimensions() + "D.";
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

		final int pruneIndex = AnalyzeSkeleton_.SHORTEST_BRANCH;
		final boolean pruneEnds = false; // Don't prune branch ends.
		final boolean shortPath = false; // Don't compute shortest path.
		final boolean silent = true;
		final boolean verbose = false;

		final IntervalView< T > intervalView = Views.interval( img, interval );
		final IntervalView< T > crop = Views.zeroMin( intervalView );
		final ImagePlus imp = ImageJFunctions.wrap( crop, "Input for AnalyseSkeleton" );
		/*
		 * Duplicate it on a non-virtual image, otherwise Analyze Skeleton does
		 * not work.
		 */
		final ImagePlus frame = new Duplicator().run( imp );

		skelAnalyzer.setup( "", frame );
		final SkeletonResult result = skelAnalyzer.run( pruneIndex, pruneEnds, shortPath, frame, silent, verbose, null );

		final long startx = interval.numDimensions() > 0 ? interval.min( 0 ) : 0;
		final long starty = interval.numDimensions() > 1 ? interval.min( 1 ) : 0;
		final long startz = interval.numDimensions() > 2 ? interval.min( 2 ) : 0;

		final List< Point > endPoints = result.getListOfEndPoints();
		for ( final Point point : endPoints )
		{
			final double x = ( startx + point.x ) * calibration[ 0 ];
			final double y = ( starty + point.y ) * calibration[ 1 ];
			final double z = ( startz + point.z ) * calibration[ 2 ];
			final double radius = 0.5;
			final double quality = END_POINTS_QUALITY_VALUE;
			final Spot spot = new Spot( x, y, z, radius, quality );
			spots.add( spot );
		}

		/*
		 * Deal with junction voxels.
		 */

		// Cluster junction pixels.
		final List< DoublePoint > junctionPixels = new ArrayList<>( result.getListOfJunctionVoxels().size() );
		for ( final Point point : result.getListOfJunctionVoxels() )
		{
			final double x = ( startx + point.x );
			final double y = ( starty + point.y );
			final double z = ( startz + point.z );
			junctionPixels.add( new DoublePoint( new double[] { x, y, z } ) );
		}
		final DBSCANClusterer< DoublePoint > clusterer = new DBSCANClusterer<>( 3., 1 );
		final List< Cluster< DoublePoint > > clusters = clusterer.cluster( junctionPixels );

		// Create one spot per cluster.
		for ( final Cluster< DoublePoint > cluster : clusters )
		{
			final double minX = cluster.getPoints().stream()
					.mapToDouble( p -> ( startx + p.getPoint()[ 0 ] ) * calibration[ 0 ] )
					.min().getAsDouble();
			final double maxX = cluster.getPoints().stream()
					.mapToDouble( p -> ( startx + p.getPoint()[ 0 ] ) * calibration[ 0 ] )
					.max().getAsDouble();
			final double meanX = cluster.getPoints().stream()
					.mapToDouble( p -> ( startx + p.getPoint()[ 0 ] ) * calibration[ 0 ] )
					.average().getAsDouble();

			final double minY = cluster.getPoints().stream()
					.mapToDouble( p -> ( starty + p.getPoint()[ 1 ] ) * calibration[ 1 ] )
					.min().getAsDouble();
			final double maxY = cluster.getPoints().stream()
					.mapToDouble( p -> ( starty + p.getPoint()[ 1 ] ) * calibration[ 1 ] )
					.max().getAsDouble();
			final double meanY = cluster.getPoints().stream()
					.mapToDouble( p -> ( starty + p.getPoint()[ 1 ] ) * calibration[ 1 ] )
					.average().getAsDouble();

			final double minZ = cluster.getPoints().stream()
					.mapToDouble( p -> ( startz + p.getPoint()[ 2 ] ) * calibration[ 2 ] )
					.min().getAsDouble();
			final double maxZ = cluster.getPoints().stream()
					.mapToDouble( p -> ( startz + p.getPoint()[ 2 ] ) * calibration[ 2 ] )
					.max().getAsDouble();
			final double meanZ = cluster.getPoints().stream()
					.mapToDouble( p -> ( startz + p.getPoint()[ 2 ] ) * calibration[ 2 ] )
					.average().getAsDouble();

			final double dx = maxX - minX;
			final double dy = maxY - minY;
			final double dz = maxZ - minZ;
			final double radius = 0.5 * Math.sqrt( dx * dx + dy * dy + dz * dz );
			final double quality = JUNCTION_POINTS_QUALITY_VALUE;
			final Spot spot = new Spot( meanX, meanY, meanZ, radius, quality );
			spots.add( spot );
		}

		final long end = System.currentTimeMillis();
		this.processingTime = end - start;

		return true;
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
