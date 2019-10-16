package fr.pasteur.iah.dendritedynamicstracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.imagej.ops.special.function.AbstractUnaryFunctionOp;
import net.imglib2.RealLocalizable;
import net.imglib2.algorithm.MultiThreaded;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.RealType;

import org.scijava.ItemIO;
import org.scijava.app.StatusService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fr.pasteur.iah.dendritedynamicstracker.SkeletonKeyPointsDetector.DetectionResults;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalysis;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalysis.Branch;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalysis.Junction;
import fr.pasteur.iah.dendritedynamicstracker.skeleton.SkeletonAnalyzer;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.ChannelSplitter;
import ij.plugin.Duplicator;

@SuppressWarnings( "deprecation" )
@Plugin( type = SkeletonKeyPointsDetector.class )
public class SkeletonKeyPointsDetector extends AbstractUnaryFunctionOp< ImagePlus, DetectionResults > implements MultiThreaded
{

	private final static double END_POINTS_QUALITY_VALUE = 1.;

	private final static double JUNCTION_POINTS_QUALITY_VALUE = 2.;

	private final static double END_POINTS_RADIUS = 0.5;

	private final static double JUNCTION_POINTS_RADIUS = 1.;

	@Parameter
	private LogService log;

	@Parameter
	private StatusService status;

	@Parameter( type = ItemIO.INPUT, label = "In what channel is the skeleton?" )
	private int skeletonChannel = 2;

	@Parameter( type = ItemIO.INPUT, label = "In what channel is raw data?" )
	private int dataChannel = 1;

	@Parameter( type = ItemIO.INPUT, label = "Prunning method" )
	private int prunningMethod = 3;

	private int numThreads;

	public SkeletonKeyPointsDetector()
	{
		setNumThreads();
	}

	@Override
	public DetectionResults calculate( final ImagePlus imp )
	{

		final ImagePlus[] channels = ChannelSplitter.split( imp );
		if ( channels.length < skeletonChannel )
		{
			log.error( "Skeleton channel defined as " + skeletonChannel + " but source image only has " + channels.length + " channels." );
			return null;
		}
		if ( channels.length < dataChannel )
		{
			log.error( "Raw data channel defined as " + dataChannel + " but source image only has " + channels.length + " channels." );
			return null;
		}

		final ImagePlus skeleton = channels[ skeletonChannel - 1 ];
		final ImagePlus origImp = channels[ dataChannel - 1 ];

		final Roi roi = imp.getRoi();
		skeleton.setRoi( roi );
		origImp.setRoi( roi );
		final int[] start = new int[] { 
				null == roi ? 0 : roi.getBounds().x, 
				null == roi ? 0 : roi.getBounds().y, 
				0 };

		final double[] calibration = new double[] { 
				imp.getCalibration().pixelWidth, 
				imp.getCalibration().pixelHeight, 
				imp.getCalibration().pixelDepth 
		};
		final double frameInterval = imp.getCalibration().frameInterval;

		final int nFrames = skeleton.getNFrames();
		final int firstZ = 1;
		final int lastZ = skeleton.getNSlices();

		status.showStatus( "Processing skeleton." );

		final AtomicInteger progress = new AtomicInteger( 0 );
		final AtomicInteger ai = new AtomicInteger( 0 );
		final Thread[] threads = SimpleMultiThreading.newThreads( numThreads );

		// Maps a skeleton end-point to its junction spot - one per thread.
		final List< Map< Spot, Spot > > junctionMapList = new ArrayList<>( threads.length );

		// Map of frame vs junctions found in this frame - one per thread.
		final List< Map< Integer, Collection< Spot > > > junctionsList = new ArrayList<>( threads.length );

		// Map of frame vs end-points found in this frame - one per thread.
		final List< Map< Integer, Collection< Spot > > > endPointsList = new ArrayList<>( threads.length );

		for ( int ithread = 0; ithread < threads.length; ithread++ )
		{

			final Duplicator duplicator = new Duplicator();

			final Map< Spot, Spot > junctionMapLocal = new HashMap<>();
			junctionMapList.add( junctionMapLocal );

			final Map< Integer, Collection< Spot > > junctionsLocal = new HashMap<>();
			junctionsList.add( junctionsLocal );

			final Map< Integer, Collection< Spot > > endPointsLocal = new HashMap<>();
			endPointsList.add( endPointsLocal );

			threads[ ithread ] = new Thread( "Detection thread " + ( 1 + ithread ) + "/" + threads.length )
			{

				@SuppressWarnings( "unchecked" )
				@Override
				public void run()
				{
					for ( int frame = ai.getAndIncrement(); frame < nFrames; frame = ai.getAndIncrement() )
					{

						// Collection of junctions found in this frame.
						final List< Spot > junctions = new ArrayList<>();

						// Collection of end-points found in this frame.
						final List< Spot > endPoints = new ArrayList<>();

						final ImagePlus skeletonFrame = duplicator.run( skeleton, 1, 1, firstZ, lastZ, frame + 1, frame + 1 );

						// For when we do prunning loop.
						// final ImagePlus origImpFrame = duplicator.run(
						// origImp, 1, 1, firstZ, lastZ, frame + 1, frame + 1 );

						final Img< ? > rawImg = ImageJFunctions.wrap( skeletonFrame );
						@SuppressWarnings( "rawtypes" )
						final Img< RealType > img = ( Img< RealType > ) rawImg;
						final SkeletonAnalysis skeletonAnalysis = SkeletonAnalyzer.analyze( img );

						/*
						 * Find junctions.
						 */
						final Map< Junction, Spot > junctionSpots = new HashMap<>();
						for ( final Junction junction : skeletonAnalysis.getJunctions() )
						{
							final Spot spot = locToSpot( junction, calibration, start, true );
							spot.putFeature( Spot.POSITION_T, frame * frameInterval );
							junctions.add( spot );
							junctionSpots.put( junction, spot );
						}

						for ( final Branch branch : skeletonAnalysis.getBranches() )
						{
							final Spot spot = locToSpot( branch.getEndPoint(), calibration, start, false );
							spot.putFeature( Spot.POSITION_T, frame * frameInterval );
							endPoints.add( spot );

							if ( null != branch.getJunction() )
							{
								final Spot junctionSpot = junctionSpots.get( branch.getJunction() );
								junctionMapLocal.put( spot, junctionSpot );
							}
						}

						junctionsLocal.put( Integer.valueOf( frame ), junctions );
						endPointsLocal.put( Integer.valueOf( frame ), endPoints );

						status.showProgress( progress.incrementAndGet(), nFrames );
					}
				}
			};
		}
		SimpleMultiThreading.startAndJoin( threads );

		/*
		 * We now collect what every single thread did on its side.
		 */

		// Maps a skeleton end-point to its junction spot.
		final Map< Spot, Spot > junctionMap = new HashMap<>();
		for ( final Map< Spot, Spot > map : junctionMapList )
			junctionMap.putAll( map );

		final SpotCollection junctionsSpots = new SpotCollection();
		for ( final Map< Integer, Collection< Spot > > map : junctionsList )
			for ( final Integer frame : map.keySet() )
				junctionsSpots.put( frame, map.get( frame ) );
		junctionsSpots.setVisible( true );

		final SpotCollection endPointSpots = new SpotCollection();
		for ( final Map< Integer, Collection< Spot > > map : endPointsList )
			for ( final Integer frame : map.keySet() )
				endPointSpots.put( frame, map.get( frame ) );
		endPointSpots.setVisible( true );

		return new DetectionResults( junctionsSpots, endPointSpots, junctionMap );

	}

	private static final Spot locToSpot( final RealLocalizable pos, final double[] calibration, final int[] start, final boolean isJunction )
	{
		final double x = ( start[ 0 ] + pos.getDoublePosition( 0 ) ) * calibration[ 0 ];
		final double y = ( start[ 1 ] + pos.getDoublePosition( 1 ) ) * calibration[ 1 ];
		final double z;
		if ( pos.numDimensions() > 2 )
			z = ( start[ 2 ] + pos.getDoublePosition( 2 ) ) * calibration[ 2 ];
		else
			z = start[ 2 ] * calibration[ 2 ];
		final double radius = isJunction ? JUNCTION_POINTS_RADIUS : END_POINTS_RADIUS;
		final double quality = isJunction ? JUNCTION_POINTS_QUALITY_VALUE : END_POINTS_QUALITY_VALUE;
		final Spot spot = new Spot( x, y, z, radius, quality );
		return spot;
	}

	public static final class DetectionResults
	{

		public final SpotCollection junctionsSpots;

		public final SpotCollection endPointSpots;

		/**
		 * Maps a skeleton end-point spot to its junction spot.
		 */
		public final Map< Spot, Spot > junctionMap;

		public DetectionResults( final SpotCollection junctionsSpots, final SpotCollection endPointSpots, final Map< Spot, Spot > junctionMap )
		{
			this.junctionsSpots = junctionsSpots;
			this.endPointSpots = endPointSpots;
			this.junctionMap = junctionMap;
		}
	}

	@Override
	public void setNumThreads()
	{
		this.numThreads = Runtime.getRuntime().availableProcessors();
	}

	@Override
	public void setNumThreads( final int numThreads )
	{
		this.numThreads = numThreads;
	}

	@Override
	public int getNumThreads()
	{
		return numThreads;
	}

}
