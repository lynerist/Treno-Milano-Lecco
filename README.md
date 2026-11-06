# Treno Lecco ⇄ Milano

App Android + widget che mostra il prossimo treno Lecco ⇄ Milano Porta Garibaldi, se è in orario o in ritardo (e di quanto), e avvisa quando i dati dell'API sono vecchi o il servizio non risponde.

## Come funziona
- **Quando controlla**: solo lun–ven, 07:00–09:00 e 16:00–19:00 (fuso Europe/Rome). Fuori fascia il widget dice "Fuori orario" e quando riprende. Gli orari si cambiano in `Config.kt`.
- **Direzioni**: widget e app mostrano due sezioni separate, "Lecco → Milano P.G." e "Milano P.G. → Lecco", ciascuna con i prossimi treni. Gli orari evidenziati sono sempre quelli della stazione di Lecco: partenza da Lecco verso Milano, arrivo a Lecco da Milano.
- **Aggiornamento**: ogni 3 minuti dentro le fasce attive, con sveglie di sistema (AlarmManager) che partono puntuali alle 7:00 e alle 16:00 e a fine fascia fanno comparire "Fuori orario". Un controllo di sicurezza ogni 15 minuti riarma le sveglie se il telefono le blocca. Nell'app ci sono i pulsanti per consentire le sveglie precise e escludere l'app dal risparmio batteria.
- **Dati**: API non ufficiale di ViaggiaTreno (include i treni Trenord). Trenord non ha un'API pubblica ufficiale.
- **Avvisi nel widget** (⚠): servizio non raggiungibile, dati vecchi di N minuti, rilevamento del treno fermo da oltre 10 minuti, servizio lento.

## Come ottenere l'APK
1. Crea un repository GitHub e carica tutto il contenuto di questa cartella (branch `main`).
2. Vai su **Actions → Build APK**: parte da solo al push (o con *Run workflow*).
3. A fine build scarica l'artefatto **TrenoLecco-debug-apk**, estrai `app-debug.apk` e installalo sul telefono (serve consentire l'installazione da fonti sconosciute).
4. Apri l'app una volta, poi tieni premuto sulla home → Widget → **Treno Lecco**.

## Limiti noti
- Il ritardo di un treno compare solo dopo che è partito dal capolinea; prima il widget scrive "Nessun ritardo".
- I giorni festivi infrasettimanali non sono esclusi (il controllo parte comunque).
- Su alcuni telefoni (Oppo/Realme/Xiaomi) il risparmio energetico può rallentare gli aggiornamenti: apri l'app e usa i due pulsanti "Per aggiornarsi in orario".
- Il significato del campo `provvedimento` (1 = soppresso, 2 = soppresso in parte) è dedotto, da verificare con un caso reale.
