package ru.krmonitor.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Lightweight vector medical pictograms for profile cards. */
public class ProfileIconView extends View {
    private final String profile;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path p = new Path();

    private static final int BLUE = Color.rgb(37,99,199);
    private static final int BLUE_SOFT = Color.rgb(235,243,255);

    public ProfileIconView(Context context, String profile) {
        super(context);
        this.profile = profile == null ? "" : profile;
        line.setColor(BLUE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        soft.setColor(BLUE_SOFT);
        soft.setStyle(Paint.Style.FILL);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w=getWidth(), h=getHeight();
        float s=Math.min(w,h)/48f;
        canvas.save();
        canvas.translate((w-48f*s)/2f,(h-48f*s)/2f);
        canvas.scale(s,s);
        canvas.drawCircle(24,24,23,soft);
        line.setStrokeWidth(2.2f);

        switch(profile) {
            case "Кардиология": heart(canvas,false); break;
            case "Сердечно-сосудистая хирургия": heart(canvas,true); break;
            case "Неврология": brain(canvas,false); break;
            case "Нейрохирургия": brain(canvas,true); break;
            case "Травматология и ортопедия": bone(canvas); break;
            case "Хирургия": scalpel(canvas); break;
            case "Гастроэнтерология": stomach(canvas); break;
            case "Пульмонология": lungs(canvas); break;
            case "Эндокринология": thyroid(canvas); break;
            case "Инфекционные болезни": microbe(canvas); break;
            case "Гематология": blood(canvas); break;
            case "Ревматология": joint(canvas); break;
            case "Нефрология": kidney(canvas); break;
            case "Урология": bladder(canvas); break;
            case "Акушерство и гинекология": female(canvas); break;
            case "Педиатрия и неонатология": baby(canvas); break;
            case "Онкология": cell(canvas); break;
            case "Офтальмология": eye(canvas); break;
            case "Оториноларингология": ear(canvas); break;
            case "Дерматология": skin(canvas); break;
            case "Психиатрия и наркология": psyche(canvas); break;
            case "Аллергология и иммунология": shield(canvas); break;
            case "Стоматология и ЧЛХ": tooth(canvas); break;
            case "Анестезиология и реаниматология": monitor(canvas); break;
            case "Медицинская реабилитация": rehab(canvas); break;
            default: medicalCross(canvas); break;
        }
        canvas.restore();
    }

    private void heart(Canvas c, boolean vessel) {
        p.reset();
        p.moveTo(24,36);
        p.cubicTo(20,32,11,27,11,19);
        p.cubicTo(11,13,18,10,24,16);
        p.cubicTo(30,10,37,13,37,19);
        p.cubicTo(37,27,28,32,24,36);
        c.drawPath(p,line);
        if(vessel){
            p.reset(); p.moveTo(22,16); p.cubicTo(22,10,25,8,28,8); p.cubicTo(32,8,34,11,34,14); c.drawPath(p,line);
            p.reset(); p.moveTo(28,8); p.cubicTo(31,12,34,13,39,13); c.drawPath(p,line);
        }
    }

    private void brain(Canvas c, boolean surgery) {
        p.reset();
        p.moveTo(23,12); p.cubicTo(18,9,13,12,14,17); p.cubicTo(9,18,9,25,13,27);
        p.cubicTo(10,31,14,37,19,35); p.cubicTo(21,39,26,37,26,33);
        p.cubicTo(31,38,37,34,35,29); p.cubicTo(41,27,39,19,35,18);
        p.cubicTo(36,13,30,10,26,13); p.cubicTo(25,11,24,11,23,12); c.drawPath(p,line);
        c.drawLine(24,14,24,34,line);
        c.drawLine(15,20,21,21,line); c.drawLine(29,20,35,18,line);
        c.drawLine(15,28,21,27,line); c.drawLine(29,27,35,30,line);
        if(surgery){ c.drawLine(31,34,39,26,line); c.drawLine(34,36,41,29,line); c.drawLine(38,26,41,29,line); }
    }

    private void bone(Canvas c) {
        p.reset();
        p.moveTo(15,15); p.cubicTo(11,11,7,14,9,18); p.cubicTo(10,20,12,20,14,20);
        p.lineTo(29,35); p.cubicTo(29,38,30,40,33,40); p.cubicTo(37,42,40,38,37,35);
        p.cubicTo(35,32,33,33,31,33); p.lineTo(16,18); p.cubicTo(17,17,17,16,15,15); c.drawPath(p,line);
        p.reset(); p.moveTo(12,12); p.cubicTo(10,9,6,10,7,14); c.drawPath(p,line);
    }

    private void scalpel(Canvas c) {
        p.reset(); p.moveTo(12,36); p.lineTo(29,19); p.lineTo(36,12); p.lineTo(39,15); p.lineTo(32,22); p.lineTo(15,39); p.close(); c.drawPath(p,line);
        c.drawLine(18,33,22,37,line); c.drawLine(30,18,34,22,line);
    }

    private void stomach(Canvas c) {
        p.reset(); p.moveTo(26,10); p.cubicTo(25,16,24,17,20,18); p.cubicTo(14,20,12,25,14,31);
        p.cubicTo(17,38,26,39,32,34); p.cubicTo(38,29,37,21,33,18); p.cubicTo(30,16,29,13,29,10); c.drawPath(p,line);
        p.reset(); p.moveTo(15,31); p.cubicTo(22,29,27,31,31,35); c.drawPath(p,line);
    }

    private void lungs(Canvas c) {
        c.drawLine(24,10,24,22,line); c.drawLine(24,17,19,21,line); c.drawLine(24,17,29,21,line);
        p.reset(); p.moveTo(20,18); p.cubicTo(15,18,10,25,10,32); p.cubicTo(10,38,16,39,21,35); p.lineTo(21,22); c.drawPath(p,line);
        p.reset(); p.moveTo(28,18); p.cubicTo(33,18,38,25,38,32); p.cubicTo(38,38,32,39,27,35); p.lineTo(27,22); c.drawPath(p,line);
    }

    private void thyroid(Canvas c) {
        c.drawLine(24,11,24,37,line);
        p.reset(); p.moveTo(23,20); p.cubicTo(17,14,11,17,12,25); p.cubicTo(13,31,19,31,23,27); c.drawPath(p,line);
        p.reset(); p.moveTo(25,20); p.cubicTo(31,14,37,17,36,25); p.cubicTo(35,31,29,31,25,27); c.drawPath(p,line);
        c.drawLine(22,24,26,24,line);
    }

    private void microbe(Canvas c) {
        c.drawCircle(24,24,10,line);
        for(int i=0;i<8;i++){ double a=i*Math.PI/4; float x1=24+(float)Math.cos(a)*11, y1=24+(float)Math.sin(a)*11; float x2=24+(float)Math.cos(a)*15, y2=24+(float)Math.sin(a)*15; c.drawLine(x1,y1,x2,y2,line); c.drawCircle(x2,y2,1.2f,line); }
        c.drawCircle(20,21,1.5f,line); c.drawCircle(28,25,1.5f,line); c.drawCircle(21,29,1.2f,line);
    }

    private void blood(Canvas c) {
        p.reset(); p.moveTo(24,9); p.cubicTo(21,16,14,23,14,29); p.cubicTo(14,36,18,40,24,40); p.cubicTo(30,40,34,36,34,29); p.cubicTo(34,23,27,16,24,9); c.drawPath(p,line);
        p.reset(); p.moveTo(18,30); p.cubicTo(19,34,21,36,24,36); c.drawPath(p,line);
    }

    private void joint(Canvas c) {
        p.reset(); p.moveTo(17,9); p.lineTo(18,20); p.cubicTo(18,23,21,24,24,24); p.cubicTo(27,24,29,22,30,19); p.lineTo(32,10); c.drawPath(p,line);
        p.reset(); p.moveTo(17,39); p.lineTo(18,30); p.cubicTo(19,27,21,26,24,26); p.cubicTo(27,26,30,28,30,31); p.lineTo(31,39); c.drawPath(p,line);
        c.drawCircle(24,25,3.5f,line);
    }

    private void kidney(Canvas c) {
        p.reset(); p.moveTo(28,10); p.cubicTo(36,10,39,17,37,24); p.cubicTo(35,31,30,36,24,37); p.cubicTo(19,38,15,34,16,29); p.cubicTo(17,25,21,24,23,21); p.cubicTo(25,18,23,14,28,10); c.drawPath(p,line);
        p.reset(); p.moveTo(27,20); p.cubicTo(31,22,31,28,27,31); c.drawPath(p,line);
    }

    private void bladder(Canvas c) {
        c.drawLine(17,10,19,23,line); c.drawLine(31,10,29,23,line);
        p.reset(); p.moveTo(18,23); p.cubicTo(17,34,20,39,24,39); p.cubicTo(28,39,31,34,30,23); p.cubicTo(27,25,21,25,18,23); c.drawPath(p,line);
        c.drawLine(24,39,24,42,line);
    }

    private void female(Canvas c) {
        c.drawCircle(24,18,8,line); c.drawLine(24,26,24,39,line); c.drawLine(18,33,30,33,line);
    }

    private void baby(Canvas c) {
        c.drawCircle(24,25,11,line);
        p.reset(); p.moveTo(22,13); p.cubicTo(21,9,26,8,28,11); p.cubicTo(29,14,26,15,24,14); c.drawPath(p,line);
        c.drawCircle(20,23,1,line); c.drawCircle(28,23,1,line);
        p.reset(); p.moveTo(20,29); p.cubicTo(22,32,26,32,28,29); c.drawPath(p,line);
    }

    private void cell(Canvas c) {
        c.drawCircle(24,24,13,line); c.drawCircle(24,24,5,line); c.drawCircle(17,19,1.4f,line); c.drawCircle(31,20,1.4f,line); c.drawCircle(29,31,1.4f,line); c.drawCircle(17,30,1.2f,line);
    }

    private void eye(Canvas c) {
        p.reset(); p.moveTo(8,24); p.cubicTo(15,14,33,14,40,24); p.cubicTo(33,34,15,34,8,24); c.drawPath(p,line);
        c.drawCircle(24,24,5,line); c.drawCircle(24,24,1.5f,line);
    }

    private void ear(Canvas c) {
        p.reset(); p.moveTo(30,36); p.cubicTo(24,40,18,37,20,31); p.cubicTo(22,26,28,27,29,22); p.cubicTo(30,17,27,13,23,14); p.cubicTo(18,15,16,20,17,25); c.drawPath(p,line);
        p.reset(); p.moveTo(23,21); p.cubicTo(27,18,31,22,28,27); p.cubicTo(26,30,23,29,23,33); c.drawPath(p,line);
    }

    private void skin(Canvas c) {
        p.reset(); p.moveTo(10,18); p.cubicTo(17,14,31,14,38,18); p.moveTo(10,24); p.cubicTo(17,20,31,20,38,24); p.moveTo(10,30); p.cubicTo(17,26,31,26,38,30); p.moveTo(10,36); p.cubicTo(17,32,31,32,38,36); c.drawPath(p,line);
        c.drawCircle(18,12,2,line); c.drawCircle(30,13,1.5f,line);
    }

    private void psyche(Canvas c) {
        p.reset(); p.moveTo(31,39); p.lineTo(31,33); p.cubicTo(37,29,38,21,34,15); p.cubicTo(30,9,20,9,15,15); p.cubicTo(10,21,12,30,18,33); p.lineTo(18,39); c.drawPath(p,line);
        p.reset(); p.moveTo(19,19); p.cubicTo(20,15,25,15,26,18); p.cubicTo(30,16,33,20,31,23); p.cubicTo(34,27,30,30,27,28); p.cubicTo(24,32,20,29,21,26); p.cubicTo(17,26,16,22,19,19); c.drawPath(p,line);
    }

    private void shield(Canvas c) {
        p.reset(); p.moveTo(24,9); p.lineTo(37,14); p.lineTo(35,26); p.cubicTo(34,33,29,38,24,41); p.cubicTo(19,38,14,33,13,26); p.lineTo(11,14); p.close(); c.drawPath(p,line);
        c.drawCircle(24,24,3,line); c.drawLine(24,17,24,20,line); c.drawLine(24,28,24,31,line); c.drawLine(17,24,20,24,line); c.drawLine(28,24,31,24,line);
    }

    private void tooth(Canvas c) {
        p.reset(); p.moveTo(15,12); p.cubicTo(20,8,23,12,24,13); p.cubicTo(27,10,34,9,36,15); p.cubicTo(38,21,34,27,33,33); p.cubicTo(32,39,29,41,27,36); p.lineTo(24,29); p.lineTo(21,36); p.cubicTo(19,41,16,39,15,33); p.cubicTo(14,27,10,19,12,15); p.cubicTo(13,13,14,12,15,12); c.drawPath(p,line);
    }

    private void monitor(Canvas c) {
        RectF r=new RectF(8,12,40,34); c.drawRoundRect(r,3,3,line); c.drawLine(16,39,32,39,line); c.drawLine(24,34,24,39,line);
        p.reset(); p.moveTo(11,24); p.lineTo(17,24); p.lineTo(20,19); p.lineTo(24,29); p.lineTo(28,22); p.lineTo(31,24); p.lineTo(37,24); c.drawPath(p,line);
    }

    private void rehab(Canvas c) {
        c.drawCircle(25,11,3,line); c.drawLine(24,15,22,26,line); c.drawLine(22,19,31,23,line); c.drawLine(22,26,15,34,line); c.drawLine(22,26,30,36,line); c.drawLine(17,17,22,20,line);
        p.reset(); p.moveTo(12,38); p.cubicTo(20,41,29,41,36,37); c.drawPath(p,line);
    }

    private void medicalCross(Canvas c) {
        c.drawLine(24,12,24,36,line); c.drawLine(12,24,36,24,line); c.drawCircle(24,24,14,line);
    }
}
