package fr.pasteur.iah.dendritedynamicstracker.trackmate;

import static fiji.plugin.trackmate.detection.DetectorKeys.DEFAULT_TARGET_CHANNEL;
import static fiji.plugin.trackmate.detection.DetectorKeys.KEY_TARGET_CHANNEL;
import static fiji.plugin.trackmate.io.IOUtils.readIntegerAttribute;
import static fiji.plugin.trackmate.io.IOUtils.writeAttribute;
import static fiji.plugin.trackmate.io.IOUtils.writeTargetChannel;
import static fiji.plugin.trackmate.util.TMUtils.checkMapKeys;
import static fiji.plugin.trackmate.util.TMUtils.checkParameter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.ImageIcon;

import org.jdom2.Element;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.detection.SpotDetector;
import fiji.plugin.trackmate.detection.SpotDetectorFactory;
import fiji.plugin.trackmate.gui.ConfigurationPanel;
import fiji.plugin.trackmate.util.TMUtils;
import net.imagej.ImgPlus;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;

public class SkeletonJunctionDetectorFactory< T extends RealType< T > & NativeType< T > > implements SpotDetectorFactory< T >
{

	/*
	 * CONSTANTS
	 */

	/** A string key identifying this factory. */
	public static final String DETECTOR_KEY = "SKELETON_JUNCTION_DETECTOR";

	/** The pretty name of the target detector. */
	public static final String NAME = "Skeleton junction detector";

	/** An html information text. */
	public static final String INFO_TEXT = "<html>"
			+ "This detector relies on the <b>Analyze Skeleton</b> plugin to extract "
			+ "a spot for each junction in a skeleton."
			+ "<p>"
			+ "The input image must be made a skeleton image that would work with the "
			+ "Analyze Sekeleton plugin: an 8-bit image with "
			+ "white pixels being the skeleton over black background. "
			+ "The skeleton image must be stored in the channel set by the TARGET_CHANNEL "
			+ "parameter."
			+ "<p>"
			+ "If the LOWEST_INTENSITY_VOXEL or the LOWEST_INTENSITY_BRANCH option is "
			+ "selected as a method to prune cycles, another channel of the source image "
			+ "must contain the original data. "
			+ "Then this original data must be stored in the channel set by the ORIG_CHANNEL "
			+ "parameter."
			+ "</html>";

	/**
	 * Key identifying the parameter setting the channel of the original image,
	 * required to accommodate LOWEST_INTENSITY_VOXEL and
	 * LOWEST_INTENSITY_BRANCH methods for pruning cycles.
	 * <p>
	 * Channels are here 1-numbered, meaning that "1" is the first available
	 * channel (and all images have at least this channel).
	 * <p>
	 * Expected values are {@link Integer}s greater than 1.
	 */
	public static final String KEY_ORIG_CHANNEL = "ORIG_CHANNEL";

	/**
	 * Key for identifying the pruning cycle methods.
	 * <p>
	 * These methods are those of the AnalyzeSkeleton plugin, namely,
	 * {@link AnalyzeSkeleton_#NONE}, {@link AnalyzeSkeleton_#SHORTEST_BRANCH},
	 * {@link AnalyzeSkeleton_#LOWEST_INTENSITY_VOXEL} and
	 * {@link AnalyzeSkeleton_#LOWEST_INTENSITY_BRANCH}.
	 * <p>
	 * Expected values are {@link Integer}s corresponding to the constants
	 * mentioned above.
	 */
	private static final String KEY_PRUNING_CYLE_METHOD = "PRUNING_CYCE_METHOD";

	/*
	 * FIELDS
	 */

	/** The image to operate on. Multiple frames, single channel. */
	protected ImgPlus< T > img;

	protected Map< String, Object > settings;

	protected String errorMessage;

	@Override
	public String getInfoText()
	{
		return INFO_TEXT;
	}

	@Override
	public ImageIcon getIcon()
	{
		return null;
	}

	@Override
	public String getKey()
	{
		return DETECTOR_KEY;
	}

	@Override
	public String getName()
	{
		return NAME;
	}

	@Override
	public ConfigurationPanel getDetectorConfigurationPanel( final Settings settings, final Model model )
	{
		// Not used in the GUI yet.
		return null;
	}

	@Override
	public SpotDetector< T > getDetector( final Interval interval, final int frame )
	{
		final double[] calibration = TMUtils.getSpatialCalibration( img );

		final int skeletonChannel = ( Integer ) settings.get( KEY_TARGET_CHANNEL ) - 1;
		final RandomAccessible< T > imSkeletonFrame = prepareFrameImg( frame, skeletonChannel );

		final int prunningMethod = ( Integer ) settings.getOrDefault( KEY_PRUNING_CYLE_METHOD, AnalyzeSkeleton_.SHORTEST_BRANCH );

		final RandomAccessible< T > imOrigFrame;
		if ( prunningMethod == AnalyzeSkeleton_.LOWEST_INTENSITY_BRANCH || prunningMethod == AnalyzeSkeleton_.LOWEST_INTENSITY_VOXEL )
		{
			final int origChannel = ( Integer ) settings.get( KEY_ORIG_CHANNEL ) - 1;
			imOrigFrame = prepareFrameImg( frame, origChannel );
		}
		else
		{
			imOrigFrame = imSkeletonFrame;
		}

		final SkeletonJunctionDetector< T > detector = new SkeletonJunctionDetector<>( imSkeletonFrame, imOrigFrame, prunningMethod, interval, calibration );
		return detector;
	}

	@Override
	public boolean setTarget( final ImgPlus< T > img, final Map< String, Object > settings )
	{
		this.img = img;
		this.settings = settings;
		return checkSettings( settings );
	}

	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}

	@Override
	public boolean marshall( final Map< String, Object > lSettings, final Element element )
	{
		final StringBuilder errorHolder = new StringBuilder();
		boolean ok = true;
		ok = ok & writeTargetChannel( lSettings, element, errorHolder );
		ok = ok & writeAttribute( lSettings, element, KEY_ORIG_CHANNEL, Integer.class, errorHolder );
		ok = ok & writeAttribute( lSettings, element, KEY_PRUNING_CYLE_METHOD, Integer.class, errorHolder );

		if ( !ok )
			errorMessage = errorHolder.toString();

		return ok;
	}

	@Override
	public boolean unmarshall( final Element element, final Map< String, Object > lSettings )
	{
		lSettings.clear();
		final StringBuilder errorHolder = new StringBuilder();
		boolean ok = true;
		ok = ok & readIntegerAttribute( element, lSettings, KEY_TARGET_CHANNEL, errorHolder );
		ok = ok & readIntegerAttribute( element, lSettings, KEY_ORIG_CHANNEL, errorHolder );
		ok = ok & readIntegerAttribute( element, lSettings, KEY_PRUNING_CYLE_METHOD, errorHolder );
		if ( !ok )
		{
			errorMessage = errorHolder.toString();
			return false;
		}
		return checkSettings( lSettings );
	}

	@Override
	public Map< String, Object > getDefaultSettings()
	{
		final Map< String, Object > lSettings = new HashMap<>();
		lSettings.put( KEY_TARGET_CHANNEL, DEFAULT_TARGET_CHANNEL );
		lSettings.put( KEY_ORIG_CHANNEL, DEFAULT_TARGET_CHANNEL );
		lSettings.put( KEY_PRUNING_CYLE_METHOD, AnalyzeSkeleton_.SHORTEST_BRANCH );
		return lSettings;
	}

	@Override
	public boolean checkSettings( final Map< String, Object > lSettings )
	{
		boolean ok = true;
		final StringBuilder errorHolder = new StringBuilder();
		ok = ok & checkParameter( lSettings, KEY_TARGET_CHANNEL, Integer.class, errorHolder );
		ok = ok & checkParameter( lSettings, KEY_ORIG_CHANNEL, Integer.class, errorHolder );
		ok = ok & checkParameter( lSettings, KEY_PRUNING_CYLE_METHOD, Integer.class, errorHolder );
		final List< String > mandatoryKeys = new ArrayList<>();
		mandatoryKeys.add( KEY_TARGET_CHANNEL );
		ok = ok & checkMapKeys( lSettings, mandatoryKeys, null, errorHolder );
		if ( !ok )
			errorMessage = errorHolder.toString();

		return ok;
	}

	protected RandomAccessible< T > prepareFrameImg( final int frame, final int channel )
	{
		final double[] calibration = TMUtils.getSpatialCalibration( img );
		RandomAccessible< T > imFrame;
		final int cDim = TMUtils.findCAxisIndex( img );
		if ( cDim < 0 )
		{
			imFrame = img;
		}
		else
		{
			// In ImgLib2, dimensions are 0-based.
			imFrame = Views.hyperSlice( img, cDim, channel );
		}

		int timeDim = TMUtils.findTAxisIndex( img );
		if ( timeDim >= 0 )
		{
			if ( cDim >= 0 && timeDim > cDim )
			{
				timeDim--;
			}
			imFrame = Views.hyperSlice( imFrame, timeDim, frame );
		}

		// In case we have a 1D image.
		if ( img.dimension( 0 ) < 2 )
		{ // Single column image, will be rotated internally.
			calibration[ 0 ] = calibration[ 1 ]; // It gets NaN otherwise
			calibration[ 1 ] = 1;
			imFrame = Views.hyperSlice( imFrame, 0, 0 );
		}
		if ( img.dimension( 1 ) < 2 )
		{ // Single line image
			imFrame = Views.hyperSlice( imFrame, 1, 0 );
		}

		return imFrame;
	}
}
