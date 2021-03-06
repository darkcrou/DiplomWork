/**
 * Created by crou on 10.03.15.
 */

import org.bytedeco.javacpp.*;
import org.bytedeco.javacv.CanvasFrame;

import java.awt.*;
import java.awt.event.InputEvent;
import java.util.*;
import java.util.List;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_highgui.*;
import static org.bytedeco.javacpp.opencv_imgproc.*;

public class Main {

    private static int hueMin = 0;
    private static int hueMax = 180;
    private static int valueMin = 0;
    private static int valueMax = 255;
    private static int satMin = 0;
    private static int satMax = 255;

    private static CvCapture camera;

    private static IplImage hue;
    private static IplImage saturation;
    private static IplImage value;

    private static IplConvKernel kernel = cvCreateStructuringElementEx(3, 3, 1, 1, CV_SHAPE_ELLIPSE);

    private static CvMemStorage memory = cvCreateMemStorage(0xffff);
    private static CvMemStorage histMemory = cvCreateMemStorage(0xffff);

    private static Robot mouseRobot;

    private static String originalWindow = "original window";
    private static double screenWidth;
    private static double screenHeight;

    private static CvPoint lastTopFingerLocation;
    private static CvPoint lastCursorPosition;
    private static double frameHeight;
    private static double frameWidth;
    private static boolean canClick;
    private static boolean handDetected;
    private static boolean canMouseMove;
    private static int morphologyTimes;

    private static LinkedList<CvPoint> palmCenterBuffer;
    private static LinkedList<Float> palmRadiusBuffer;
    private static double frameLimitY;
    private static CvSeq fingerHistory;
    private static CvSeq cursorHistory;
    private static LinkedList<Integer> fingersHistory;
    private static double proportionX;
    private static double proportionY;
    private static boolean canRecordPosition;
    private static CvPoint lastThreeFingersAveragePosition;

    public static void main(String[] args) {

        //Wejście wideo
        camera = cvCreateCameraCapture(0);

        //Wysokość obrazku wchodzącego
        frameHeight = cvGetCaptureProperty(camera, CV_CAP_PROP_FRAME_HEIGHT);
        //Szerokość obrazku wchodzącego
        frameWidth = cvGetCaptureProperty(camera, CV_CAP_PROP_FRAME_WIDTH);

        //Ekran na którym znajduję się kursor
        GraphicsDevice device = CanvasFrame.getDefaultScreenDevice();
        //Wysokość ekranu
        screenHeight = device.getDefaultConfiguration().getBounds().getHeight();
        //Szerokość ekranu
        screenWidth = device.getDefaultConfiguration().getBounds().getWidth();

        //Limit położenia palca
        frameLimitY = frameHeight * 0.8;

        //Proporcje x
        proportionX = frameWidth / screenWidth;
        //Proporcje y
        proportionY = frameLimitY / screenWidth;

        morphologyTimes = 1;
        palmCenterBuffer = new LinkedList<CvPoint>();
        palmRadiusBuffer = new LinkedList<>();

        fingerHistory = cvCreateSeq(CV_SEQ_ELTYPE_POINT, Loader.sizeof(CvContour.class), Loader.sizeof(CvPoint.class), histMemory);
        cursorHistory = cvCreateSeq(CV_SEQ_ELTYPE_POINT, Loader.sizeof(CvContour.class), Loader.sizeof(CvPoint.class), histMemory);

        fingersHistory = new LinkedList<>();

        try {
            mouseRobot = new Robot(CanvasFrame.getDefaultScreenDevice());
        } catch (AWTException e) {
            System.err.print("Cannot initialize ROBOT, so you could not move the cursor");
        }

        if (camera != null) {
            cvNamedWindow(originalWindow, 0);
            cvNamedWindow("Value", 0);
            cvNamedWindow("Hue", 0);
            cvNamedWindow("Saturation", 0);

            process();

        }

        cvClearSeq(fingerHistory);
        cvClearSeq(cursorHistory);
        cvClearMemStorage(memory);
        cvClearMemStorage(histMemory);
        cvReleaseMemStorage(memory);
        cvReleaseMemStorage(histMemory);
        cvReleaseStructuringElement(kernel);
        cvReleaseCapture(camera);
        cvDestroyAllWindows();
    }

    public static int waitKey(int FPS) {
        char key = (char) cvWaitKey(FPS);
        switch (key) {
            case 'r': canRecordPosition = true; break;
            case 'R': return 3;
            case '[': if(morphologyTimes - 1 <= 0) morphologyTimes = 0; else morphologyTimes--; break;
            case ']': morphologyTimes++; break;
            case 'p': return 2;
            case 'm': canMouseMove = !canMouseMove; break;
            case 27:
            case 'q':
                return 0;
            case 'h':
                System.out.println("Hue max: " + (hueMax -= 1));
                break;
            case 'H':
                System.out.println("Hue max: " + (hueMax += 1));
                break;
            case 'x':
                System.out.println("Hue min: " + (hueMin -= 1));
                break;
            case 'X':
                System.out.println("Hue min: " + (hueMin += 1));
                break;
            case 's':
                System.out.println("Saturation max: " + (satMax -= 1));
                break;
            case 'S':
                System.out.println("Saturation max: " + (satMax += 1));
                break;
            case 'c':
                System.out.println("Saturation min: " + (satMin -= 1));
                break;
            case 'C':
                System.out.println("Saturation min: " + (satMin += 1));
                break;
            case 'w':
                System.out.println("Value min: " + (valueMin -= 1));
                break;
            case 'W':
                System.out.println("Value min: " + (valueMin += 1));
                break;
            case 'v':
                System.out.println("Value max: " + (valueMax -= 1));
                break;
            case 'V':
                System.out.println("Value max: " + (valueMax += 1));
                break;
        }
        return 1;
    }

    /**
     * Funkcja wyszukuje nawjekszy zarys na podanej masce.
     * @param mask - maska na ktorej bedzie wyszukiwany zarys
     * @return CvSeq z wyszukanym zarysem*/
     private static CvSeq findTheBiggestContour(IplImage mask) {

        cvClearMemStorage(memory);

        CvSeq contour = new CvContour(null); //Tworzymy nowy ciag dla nowego zarysu

        cvFindContours(mask, memory, contour, Loader.sizeof(CvContour.class), CV_RETR_LIST, CV_CHAIN_APPROX_SIMPLE);

        CvSeq theBiggestContour = contour;
         double minContourLength = 400;

        while (contour != null && !contour.isNull()) {
            if (contour.total() > 0) {
                double tempContourArea = cvContourArea(contour);
                double tempContourLength = cvContourPerimeter(contour);
                if (tempContourLength > minContourLength && cvContourArea(theBiggestContour) < tempContourArea) {
                    theBiggestContour = contour;
                }
            }
            contour = contour.h_next();
        }

        return theBiggestContour;
    }

    /**
     * Funkcja ktora rozdziela obrazek na 3 kanaly przestrzeni kolorowej HSV, filtruje kanaly i skleja z powrotem w nowoutworzona maske
     * @param original - oryginalny obrazek dla przetwarzania do maski
     * @return mask - maska */
     private static IplImage createMaskFromHSV(IplImage original) {

        IplImage mask = cvCreateImage(original.cvSize(), IPL_DEPTH_8U, 1);
        hue = cvCreateImage(original.cvSize(), IPL_DEPTH_8U, 1);
        saturation = cvCreateImage(original.cvSize(), IPL_DEPTH_8U, 1);
        value = cvCreateImage(original.cvSize(), IPL_DEPTH_8U, 1);

        cvCvtColor(original, original, CV_BGR2HSV);
        cvSplit(original, hue, saturation, value, null);

        cvInRangeS(hue, cvScalar(hueMin), cvScalar(hueMax), hue);
        cvInRangeS(value, cvScalar(valueMin), cvScalar(valueMax), value);
        cvInRangeS(saturation, cvScalar(satMin), cvScalar(satMax), saturation);

        cvDilate(hue, hue, kernel, morphologyTimes);
        cvErode(hue, hue, kernel, morphologyTimes + 3);
//        cvMorphologyEx(hue, hue, null, kernel, CV_MOP_CLOSE, morphologyTimes);

        cvNot(hue, hue);
        cvAnd(hue, value, mask);
        cvAnd(mask, saturation, mask);
        cvCvtColor(original, original, CV_HSV2BGR);

        return mask;
    }

    private static void drawSequencePolyLines(CvSeq approximation, IplImage on, CvScalar color) {
        CvPoint p0 = new CvPoint(cvGetSeqElem(approximation, approximation.total() - 1));
        for (int i = 0; i < approximation.total(); i++) {
            BytePointer pointer = cvGetSeqElem(approximation, i);
            CvPoint p = new CvPoint(pointer);
            cvLine(on, p0, p, color, 2, 8, 0);
            p0 = p;
        }
    }

    private static void drawSequenceCircles(CvSeq approximation, IplImage on, CvScalar color, int radius, int thikness) {
        for (int i = 0; i < approximation.total(); i++) {
            BytePointer pointer = cvGetSeqElem(approximation, i);
            CvPoint p = new CvPoint(pointer);
            cvCircle(on, p, radius, color, thikness, 7, 0);
        }
    }

    private static CvSeq findFingers(CvSeq dominantPoints, CvPoint palmCenter, float radius) {
        if(dominantPoints == null) throw new RuntimeException("Given dominantPoints is NULL");
        if(dominantPoints.isNull()) throw new RuntimeException("Given dominantPoints has NULL values");
        int dominantCounts = dominantPoints.total();
        if(dominantCounts == 0) throw new RuntimeException("Given dominantPoints has no values");

        CvSeq fingers = cvCreateSeq(CV_SEQ_ELTYPE_POINT, Loader.sizeof(CvSeq.class), Loader.sizeof(CvPoint.class), memory);

        for(int i = 0; i < dominantCounts; i++) {
            CvPoint dominantPoint = new CvPoint(cvGetSeqElem(dominantPoints, i));
            double distance = vectorLength(vector(dominantPoint, palmCenter));
            double distanceFromBottom = vectorLength(vector(dominantPoint,cvPoint(dominantPoint.x(), (int) frameHeight)));
            if(distance > radius * 1.35 && distance <= radius * 3 && distanceFromBottom > frameHeight * 0.1) {
                cvSeqPush(fingers, dominantPoint);
            }
        }

        if(fingers.total() == 5) handDetected = true;
        return fingers;
    }

    private static CvSeq findDominantPoints(CvSeq contour, int minDist, int angle, CvPoint centerOfPalm) {
        CvSeq points = cvCreateSeq(CV_SEQ_ELTYPE_POINT, Loader.sizeof(contour.getClass()),
                    Loader.sizeof(CvPoint.class), memory);
        double angleCosine = Math.cos(angle);
        LinkedList<CvPoint> group = new LinkedList<>();

        for(int i = 0; i < contour.total(); i++) {
            CvPoint p0 = new CvPoint(cvGetSeqElem(contour, i % contour.total()));

            CvPoint p = new CvPoint(cvGetSeqElem(contour, (i + minDist) % contour.total()));
            CvPoint p1 = new CvPoint(cvGetSeqElem(contour, (i + minDist * 2) % contour.total()));

            int [] vector1 = vector(p0, p);
            int [] vector2 = vector(p1, p);
            double dotProduct = vectorDotProduct(vector1, vector2);
            double cosine = dotProduct / vectorLength(vector1) / vectorLength(vector2);

            if(cosine > angleCosine) {
                group.push(p);
            } else if(group.size() > 0) {
                CvPoint dominant = localDominant(group);
                if(dominant != null) cvSeqPush(points, dominant);
                group.clear();
            }
        }

        return points;
    }

    public static CvPoint localDominant(List segment) {
        if(segment == null) throw new RuntimeException("Your local contour is NULL");
        int contourLength = segment.size();
        CvPoint localDominant = null;
        if(contourLength < 3){
            if(contourLength == 2) {
                CvPoint first = (CvPoint) segment.get(0);
                CvPoint second = (CvPoint) segment.get(1);
                localDominant = new CvPoint();
                localDominant.x((first.x() + second.x())/2);
                localDominant.y((first.y() + second.y())/2);
                return localDominant;
            }
            if(contourLength == 1) return (CvPoint)segment.get(0);
            return localDominant;
        }

        CvPoint first = (CvPoint)segment.get(0);
        CvPoint last = (CvPoint)segment.get(contourLength - 1);
        double theLongestVector = 0;
        int [] mainVector = vector(first, last);

        for(int i = 1; i < contourLength - 1; i++) {
            CvPoint temp = (CvPoint)segment.get(i);

            double tempLength = Math.abs((mainVector[1] * temp.x() - mainVector[0] * temp.y() + last.x()*first.y() - last.y()*first.x())) / Math.sqrt(vectorLength(mainVector));

            if(tempLength > theLongestVector) {
                localDominant = temp;
                theLongestVector = tempLength;
            }
        }

        return localDominant;
    }

    private static double vectorLength(int [] vector) {
        return Math.sqrt(vector[0]*vector[0] + vector[1]*vector[1]);
    }

    private static double vectorLength(double [] vector) {
        return Math.sqrt(vector[0]*vector[0] + vector[1]*vector[1]);
    }

    private static int vectorDotProduct(int [] vector1, int [] vector2) {
        return vector1[0]*vector2[0] + vector1[1]*vector2[1];
    }

    private static int[] vector(CvPoint first, CvPoint second) {
        if(first == null) throw new RuntimeException("Cannot count vector 'cuz FIRST point is NULL");
        if(second == null) throw new RuntimeException("Cannot count vector 'cuz SECOND point is NULL");
        if(first.isNull()) throw new RuntimeException("Cannot count vector 'cuz FIRST point has NULL data");
        if(second.isNull()) throw new RuntimeException("Cannot count vector 'cuz SECOND point has NULL data");

        return  new int[]{
                second.x() - first.x(),
                second.y() - first.y()
        };
    }

    public static CvSeq findDeepestPoints(CvSeq convexityDefects) {
        if(convexityDefects == null) throw new RuntimeException("Given convexityDefects is NULL");
        if(convexityDefects.isNull()) throw new RuntimeException("Given convexityDefects has NULL values");
        if(convexityDefects.total() == 0) throw new RuntimeException("Given convexityDefects has no values");

        CvSeq depthPoints = cvCreateSeq(CV_SEQ_ELTYPE_POINT, Loader.sizeof(CvContour.class), Loader.sizeof(CvPoint.class), memory);

        if(convexityDefects != null && !convexityDefects.isNull() && convexityDefects.total() > 0) {

            CvPoint tempPoint = new CvConvexityDefect(cvGetSeqElem(convexityDefects, convexityDefects.total() - 1)).end();

            for (int i = 0; i < convexityDefects.total(); i++) {
                CvConvexityDefect defect = new CvConvexityDefect(cvGetSeqElem(convexityDefects, i));
                if(defect.depth() > 10) { //correct me
                    cvSeqPush(depthPoints, defect.depth_point());

                    defect.start(tempPoint);

                    tempPoint = defect.end();
                }
            }
        }
        return depthPoints;
    }

    public static CvPoint averagePosition(CvSeq contour) {
        if(contour == null) throw new RuntimeException("Given contour is NULL");
        if(contour.isNull()) throw new RuntimeException("Given contour has NULL values");
        if(contour.total() == 0) throw new RuntimeException("Given contour has no values");

        int x = 0;
        int y = 0;
        for (int i = 0; i < contour.total(); i++) {
            CvPoint tmp = new CvPoint(cvGetSeqElem(contour, i));
                x += tmp.x();
                y += tmp.y();
        }

        return cvPoint(x / contour.total(), y / contour.total());
    }

    public static CvPoint theHighestPoint(CvSeq contour) {
        if(contour == null) throw new RuntimeException("Given contour is NULL");
        if(contour.isNull()) throw new RuntimeException("Given contour has NULL values");
        if(contour.total() == 0) throw new RuntimeException("Given contour has no values");

        CvPoint theHighest = new CvPoint(cvGetSeqElem(contour, 0));
        for(int i = 0; i < contour.total(); i++) {
            CvPoint tmp = new CvPoint(cvGetSeqElem(contour, i));
            if(theHighest.y() > tmp.y()) theHighest = tmp;
        }
        return theHighest;
    }

    private static void process() {
        mainloop:
        for (;;) {

            IplImage original = cvQueryFrame(camera);
            cvFlip(original, original, 2);

            IplImage mask = createMaskFromHSV(original);

            CvSeq theBiggestContour = findTheBiggestContour(mask);

            if (theBiggestContour != null && !theBiggestContour.isNull()) {

                drawSequencePolyLines(theBiggestContour, original, CvScalar.RED);
                CvMoments moments = new CvMoments();

                cvMoments(theBiggestContour, moments);

                CvPoint centerOfTheArm = new CvPoint();

                if(moments.m00()!= 0) {
                    centerOfTheArm.x(((int) (moments.m10() / moments.m00())));
                    centerOfTheArm.y((int) (moments.m01() / moments.m00()));
                }
//                cvCircle(original, centerOfTheArm, 4, CvScalar.MAGENTA, 2, 8, 0);

                CvSeq approximation = cvApproxPoly(theBiggestContour, Loader.sizeof(CvContour.class), memory, CV_POLY_APPROX_DP, cvContourPerimeter(theBiggestContour) * 0.0015, 1);
                CvSeq convexHull = cvConvexHull2(approximation, memory, CV_CLOCKWISE, 0);
                CvSeq convexHullForDrawing = cvConvexHull2(approximation, memory, CV_CLOCKWISE, 1);
                drawSequencePolyLines(convexHullForDrawing, original, cvScalar(0xff0000));
                CvSeq convexityDefects = cvConvexityDefects(approximation, convexHull, memory);

                if(!convexityDefects.isNull() && convexityDefects.total() > 1) {
                    CvSeq theDeepestPoints = findDeepestPoints(convexityDefects);
                    drawSequenceCircles(theDeepestPoints, original, cvScalar(0, 255, 255, 255), 3, 3);
                    if(theDeepestPoints.total() < 2) {
                        CvPoint theHighestPoint = theHighestPoint(theBiggestContour);
                        cvCircle(original, theHighestPoint, 6, cvScalar(255, 0, 255, 255), 8, 8, 0);
                        cvSeqPush(theDeepestPoints, theHighestPoint);
                    }


                    float [] tempEnclosingCircleCenter = new float[2];
                    float [] enclosingCircleRadius = new float[1];

                    if(theDeepestPoints.total() > 1) {
                        cvMinEnclosingCircle(theDeepestPoints, tempEnclosingCircleCenter, enclosingCircleRadius);
                        CvPoint enclosingCircleCenter = cvPoint(((int) tempEnclosingCircleCenter[0]), ((int)tempEnclosingCircleCenter[1]));
                        CvPoint averageDepthPointPosition = averagePosition(theDeepestPoints);


                        CvPoint averageCenter = cvPoint((averageDepthPointPosition.x() + enclosingCircleCenter.x()) / 2,
                                (averageDepthPointPosition.y() + enclosingCircleCenter.y()) / 2);


                        if(palmCenterBuffer.size() > 3) {
                            int centerPosX = averageCenter.x();
                            int centerPosY = averageCenter.y();

                            palmCenterBuffer.removeFirst();

                            for (int i = 0; i < palmCenterBuffer.size(); i++) {
                                int x = palmCenterBuffer.get(i).x();
                                int y = palmCenterBuffer.get(i).y();
                                centerPosY = (centerPosY + y) / 2;
                                centerPosX = (centerPosX + x) / 2;
                            }
                            averageCenter.x(centerPosX);
                            averageCenter.y(centerPosY);
                        }

                        palmCenterBuffer.add(averageCenter);

                        if(palmRadiusBuffer.size() > 3) {
                            float radius = enclosingCircleRadius[0];

                            palmRadiusBuffer.removeFirst();

                            for (int i = 0; i < palmRadiusBuffer.size(); i++) {
                                float tmpRad = palmRadiusBuffer.get(i);
                                radius = (radius + tmpRad) / 2;
                            }
                            enclosingCircleRadius[0] = radius;
                        }

                        palmRadiusBuffer.add(enclosingCircleRadius[0]);


                        int dominantDistance = (theBiggestContour.total() * 0.03) < 25? 25: (int) (theBiggestContour.total() * 0.03);
                        CvSeq dominantPoints = findDominantPoints(theBiggestContour, dominantDistance, 70, averageCenter);

                        cvCircle(original, enclosingCircleCenter, ((int) enclosingCircleRadius[0]), AbstractCvScalar.CYAN, 2, 8, 0);
                        cvCircle(original, enclosingCircleCenter, ((int) (enclosingCircleRadius[0] * 1.3)), cvScalar(200, 200, 200, 255), 2, 8, 0);

                        if(dominantPoints.total() > 0) {
                            CvSeq fingers = findFingers(dominantPoints, averageCenter, enclosingCircleRadius[0]);
                            drawSequenceCircles(fingers, original, AbstractCvScalar.GREEN, 2, 1);

                            if(canMouseMove) {
                                if(fingers.total() == 1) {
                                    CvPoint currentFingerLocation = new CvPoint(cvGetSeqElem(fingers, 0));
                                    if(lastTopFingerLocation != null && handDetected) {
                                        int newCurPosX = (int) (currentFingerLocation.x() / proportionX);
                                        int newCurPosY = (int) (currentFingerLocation.y() / proportionY);
                                        if(lastCursorPosition == null || lastCursorPosition.x() == 0 || lastCursorPosition.y() == 0)
                                            lastCursorPosition = cvPoint(newCurPosX, newCurPosY);
                                        if(Math.abs(newCurPosX - lastCursorPosition.x()) / (lastCursorPosition.x()) < 0.01 &&
                                                Math.abs(newCurPosY - lastCursorPosition.y()) / (lastCursorPosition.y()) < 0.01 ) {
                                            lastCursorPosition.x((newCurPosX + lastCursorPosition.x()) / 2);
                                            lastCursorPosition.y((newCurPosY + lastCursorPosition.y()) / 2);
                                            mouseRobot.mouseMove(lastCursorPosition.x(), lastCursorPosition.y());
                                        }

                                        if(canRecordPosition) {
                                            cvSeqPush(cursorHistory, cvPoint(newCurPosX, newCurPosY));
                                            cvSeqPush(fingerHistory, currentFingerLocation);
                                        }

                                        if(fingersHistory.size() > 10) {
                                            int twoFingerCount = 0;
                                            for(int i = fingersHistory.size() - 1; i > 0; i--) {
                                                if(fingersHistory.get(i).equals(2)) twoFingerCount++; else break;
                                            }
                                            if(twoFingerCount > 4) {
                                                mouseRobot.mousePress(InputEvent.BUTTON1_MASK);
                                                mouseRobot.delay(100);
                                                mouseRobot.mouseRelease(InputEvent.BUTTON1_MASK);
                                            }
                                        }
                                        fingersHistory.add(1);
                                    }
                                    lastTopFingerLocation = currentFingerLocation;
                                } else if(fingers.total() == 2 && handDetected) {
                                    fingersHistory.add(2);
                                } else if(fingers.total() == 3 && handDetected) {
                                    if(fingersHistory.size() > 10) {
                                        int tmpX = 0;
                                        int tmpY = 0;
                                        for (int i = 0; i < 3; i++) {
                                            CvPoint tmp = new CvPoint(cvGetSeqElem(fingers, i));
                                            tmpX += tmp.x();
                                            tmpY += tmp.y();
                                        }
                                        CvPoint curAveragePosition = cvPoint(tmpX / 3, tmpY / 3);

                                        int lastFingersCount = fingersHistory.getLast();
                                        int preLastFingersCount = fingersHistory.get(fingersHistory.size() - 2);
                                        if (lastFingersCount == 3 && preLastFingersCount == 3) {
                                            if (lastThreeFingersAveragePosition != null) {
                                                int positionChangeDelta = lastThreeFingersAveragePosition.y() - curAveragePosition.y();
                                                if (Math.abs(positionChangeDelta) > 10) {
                                                    mouseRobot.mouseWheel((int) Math.signum(positionChangeDelta) * 3);
                                                }
                                            }
                                        }
                                        lastThreeFingersAveragePosition = curAveragePosition;
                                    }
                                    fingersHistory.add(3);
                                }
                            }
                            cvClearSeq(fingers);
                        }
                        cvClearSeq(theDeepestPoints);
                    }
                }
                //Czyszczenie pamięcie zajętej przez elementy klasy Seq
                cvClearSeq(convexityDefects);
                cvClearSeq(convexHull);
                cvClearSeq(approximation);
                cvClearSeq(theBiggestContour);
            }

            cvShowImage("Hue", hue);
            cvShowImage("Saturation", saturation);
            cvShowImage("Value", value);
//            cvShowImage("Mask", mask);
            cvShowImage(originalWindow, original);

            int keyWaitRes = waitKey(1);

            switch (keyWaitRes){
                case 2:
                    cvSaveImage("original-image-" + new Date().toString() + ".png",original);
                    cvSaveImage("hue-image-" + new Date().toString() + ".png",hue);
                    cvSaveImage("saturation-image-" + new Date().toString() + ".png",saturation);
                    cvSaveImage("value-image-" + new Date().toString() + ".png",value);
                    cvSaveImage("mask-image-" + new Date().toString() + ".png",mask);
                    break;
                case 3:
                    IplImage cursorHistImage = cvCreateImage(cvSize((int) screenWidth, (int) screenHeight), IPL_DEPTH_8U, 3);
                    cvZero(cursorHistImage);
                    drawSequenceCircles(cursorHistory, cursorHistImage, cvScalar(0, 220, 200, 255), 2, 2);
                    cvSaveImage("cursorHistory " + new Date().toString() + " .png", cursorHistImage);
                    cvReleaseImage(cursorHistImage);

                    IplImage fingerHistImage = cvCreateImage(cvSize((int) frameWidth, (int) frameHeight), IPL_DEPTH_8U, 3);
                    cvZero(fingerHistImage);
                    drawSequenceCircles(fingerHistory, fingerHistImage, cvScalar(0, 100, 200, 255), 2, 2);
                    cvSaveImage("fingerHistory " + new Date().toString() + " .png", fingerHistImage);
                    cvReleaseImage(fingerHistImage);
                    break;

                case 0: break mainloop;
            }
            //Czyszczenie pamięci utwożonych obrazów
            cvReleaseImage(value);
            cvReleaseImage(hue);
            cvReleaseImage(saturation);
            cvReleaseImage(mask);
        }

    }
}
