package ru.krmonitor.app;

import android.content.Context;
import java.util.Map;

public final class SeedImporter {
    private SeedImporter() {}

    public static void ensureSeeded(Context context) throws Exception {
        DbHelper db=new DbHelper(context);
        if(db.count()>0) return;
        Map<String,Recommendation> online=CatalogFetcher.fetch();
        for(Recommendation r:online.values()) db.upsert(r,"");
    }
}
