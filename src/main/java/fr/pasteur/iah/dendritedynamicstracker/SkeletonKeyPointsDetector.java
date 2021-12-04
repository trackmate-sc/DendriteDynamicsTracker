/*-
 * #%L
 * A Fiji plugin to track the dynamics of dendrites in 2D time-lapse movies.
 * %%
 * Copyright (C) 2019 - 2021 Institut Pasteur
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.scijava.ItemIO;
import org.scijava.app.StatusService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fr.pasteur.iah.dendritedynamicstracker.SkeletonKeyPointsDetector.DetectionResults;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.ChannelSplitter;
import ij.plugin.Duplicator;
import net.imagej.ops.special.function.AbstractUnaryFunctionOp;
import net.imglib2.algorithm.MultiThreaded;
import net.imglib2.multithreading.SimpleMultiThreading;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Edge;
import sc.fiji.analyzeSkeleton.Graph;
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.analyzeSkeleton.Vertex;

@SuppressWarnings( "deprecation" )
@Plugin( type = SkeletonKeyPointsDetector.class )
public class SkeletonKeyPointsDetector extends AbstractUnaryFunctionOp< ImagePlus, DetectionResults > implements MultiThreaded
{

	public final static double END_POINTS_QUALITY_VALUE = 1.;

	public final static double JUNCTION_POINTS_QUALITY_VALUE = 2.;

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

		final boolean pruneEnds = false; // Don't prune branch ends.
		final boolean shortPath = false; // Don't compute shortest path.
		final boolean silent = true;
		final boolean verbose = false;

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

		// Map that links spots to AS vertices - one per thread.
		final List< Map< Integer, Map< Spot, Vertex > > > spotMapList = new ArrayList<>( threads.length );

		// Map that links AS vertices to spots - one per thread.
		final List< Map< Integer, Map< Vertex, Spot > > > vertexMapList = new ArrayList<>( threads.length );


		for ( int ithread = 0; ithread < threads.length; ithread++ )
		{

			final Duplicator duplicator = new Duplicator();
			final AnalyzeSkeleton_ skelAnalyzer = new AnalyzeSkeleton_();

			final Map< Spot, Spot > junctionMapLocal = new HashMap<>();
			junctionMapList.add( junctionMapLocal );

			final Map< Integer, Collection< Spot > > junctionsLocal = new HashMap<>();
			junctionsList.add( junctionsLocal );

			final Map< Integer, Collection< Spot > > endPointsLocal = new HashMap<>();
			endPointsList.add( endPointsLocal );

			final Map< Integer, Map< Spot, Vertex > > spotMapLocal = new HashMap<>();
			spotMapList.add( spotMapLocal );

			final Map< Integer, Map< Vertex, Spot > > vertexMapLocal = new HashMap<>();
			vertexMapList.add( vertexMapLocal );

			threads[ ithread ] = new Thread( "Detection thread " + ( 1 + ithread ) + "/" + threads.length )
			{

				@Override
				public void run()
				{
					for ( int frame = ai.getAndIncrement(); frame < nFrames; frame = ai.getAndIncrement() )
					{

						// Collection of junctions found in this frame.
						final List< Spot > junctions = new ArrayList<>();

						// Collection of end-points found in this frame.
						final List< Spot > endPoints = new ArrayList<>();

						// Maps a graph vertex to the spot created from it.
						final Map< Vertex, Spot > vertexMap = new HashMap<>();

						// Maps a spot to the graph vertex that created it.
						final Map< Spot, Vertex > spotMap = new HashMap<>();

						final ImagePlus skeletonFrame = duplicator.run( skeleton, 1, 1, firstZ, lastZ, frame + 1, frame + 1 );
						final ImagePlus origImpFrame = duplicator.run( origImp, 1, 1, firstZ, lastZ, frame + 1, frame + 1 );

						skelAnalyzer.setup( "", skeletonFrame );
						final SkeletonResult result = skelAnalyzer.run( prunningMethod, pruneEnds, shortPath, origImpFrame, silent, verbose, null );

						final Graph[] graphs = result.getGraph();
						for ( final Graph graph : graphs )
						{
							final List< Vertex > vertices = graph.getVertices();

							/*
							 * Find junctions.
							 */

							for ( final Vertex vertex : vertices )
							{
								if ( vertex.getBranches().size() == 1 )
									continue;

								final Spot spot = vertexToSpot( vertex, calibration, start );
								spot.putFeature( Spot.POSITION_T, frame * frameInterval );
								vertexMap.put( vertex, spot );
								spotMap.put( spot, vertex );

								junctions.add( spot );
							}

							/*
							 * Find end points and link them to their junction.
							 */

							for ( final Vertex vertex : vertices )
							{
								if ( vertex.getBranches().size() != 1 )
									continue;

								final Spot spot = vertexToSpot( vertex, calibration, start );
								spot.putFeature( Spot.POSITION_T, frame * frameInterval );
								vertexMap.put( vertex, spot );
								spotMap.put( spot, vertex );

								endPoints.add( spot );

								// Find matching junction.
								final Edge predecessor = vertex.getBranches().get( 0 );
								if ( null == predecessor )
									continue;

								final Vertex oppositeVertex = predecessor.getOppositeVertex( vertex );
								final Spot junctionSpot = vertexMap.get( oppositeVertex );
								if ( null != junctionSpot )
									junctionMapLocal.put( spot, junctionSpot );

							}

						}

						junctionsLocal.put( Integer.valueOf( frame ), junctions );
						endPointsLocal.put( Integer.valueOf( frame ), endPoints );
						spotMapLocal.put( Integer.valueOf( frame ), spotMap );
						vertexMapLocal.put( Integer.valueOf( frame ), vertexMap );

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

		final Map<Spot, Vertex> spotMap = new HashMap<>();
		for ( final Map< Integer, Map< Spot, Vertex > > map : spotMapList )
			for ( final Integer frame : map.keySet() )
				spotMap.putAll( map.get( frame ) );

		final Map< Vertex, Spot > vertexMap = new HashMap<>();
		for ( final Map< Integer, Map< Vertex, Spot > > map : vertexMapList )
			for ( final Integer frame : map.keySet() )
				vertexMap.putAll( map.get( frame ) );

		return new DetectionResults(
				junctionsSpots,
				endPointSpots,
				junctionMap,
				spotMap,
				vertexMap  );
	}

	private static final Spot vertexToSpot( final Vertex vertex, final double[] calibration, final int[] start )
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
		final boolean isJunction = vertex.getBranches().size() > 1;
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

		private final Map< Spot, Vertex > spotMap;

		private final Map< Vertex, Spot > vertexMap;

		public DetectionResults(
				final SpotCollection junctionsSpots,
				final SpotCollection endPointSpots,
				final Map< Spot, Spot > junctionMap,
				final Map< Spot, Vertex > spotMap,
				final Map< Vertex, Spot > vertexMap )
		{
			this.junctionsSpots = junctionsSpots;
			this.endPointSpots = endPointSpots;
			this.junctionMap = junctionMap;
			this.spotMap = spotMap;
			this.vertexMap = vertexMap;
		}

		public Vertex getVertexFor( final Spot spot )
		{
			return spotMap.get( spot );
		}

		public Spot getSpotFor( final Vertex vertex )
		{
			return vertexMap.get( vertex );
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
