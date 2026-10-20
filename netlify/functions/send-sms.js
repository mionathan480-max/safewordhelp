// Version navigateur uniquement : l'app Android envoie les SMS toute seule, sans serveur.
// Variables Netlify : SAFEWORD_CODE, TWILIO_SID, TWILIO_TOKEN, TWILIO_FROM
const json = (statusCode, obj) => ({ statusCode, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(obj) });

exports.handler = async (event) => {
  if (event.httpMethod !== 'POST') return json(405, { error: 'POST uniquement' });
  if (!process.env.SAFEWORD_CODE || event.headers['x-code'] !== process.env.SAFEWORD_CODE) {
    return json(401, { error: 'Code invalide' });
  }

  let data;
  try { data = JSON.parse(event.body); } catch { return json(400, { error: 'JSON invalide' }); }

  const { TWILIO_SID, TWILIO_TOKEN, TWILIO_FROM } = process.env;
  const ready = !!(TWILIO_SID && TWILIO_TOKEN && TWILIO_FROM);
  if (data.ping) return json(200, { ok: true, twilio: ready });
  if (!ready) return json(500, { error: 'Clés Twilio manquantes', sent: 0, total: 0, errors: [{ message: 'Clés Twilio manquantes sur Netlify' }] });

  const to = (Array.isArray(data.to) ? data.to : []).slice(0, 5);
  const body = String(data.body || '').slice(0, 600);
  if (!to.length || !body) return json(400, { error: 'to et body requis' });

  const auth = Buffer.from(`${TWILIO_SID}:${TWILIO_TOKEN}`).toString('base64');
  const url = `https://api.twilio.com/2010-04-01/Accounts/${TWILIO_SID}/Messages.json`;
  const errors = [];

  const results = await Promise.all(to.map(async (n) => {
    if (!/^\+\d{8,15}$/.test(n)) { errors.push({ to: n, message: `Numéro invalide : ${n}` }); return false; }
    try {
      const r = await fetch(url, {
        method: 'POST',
        headers: { Authorization: `Basic ${auth}`, 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ To: n, From: TWILIO_FROM, Body: body }),
      });
      if (r.ok) return true;
      const j = await r.json().catch(() => ({}));
      // 21608 : compte d'essai, le numéro doit être vérifié dans la console Twilio
      errors.push({ to: n, code: j.code, message: j.code === 21608 ? 'Numéro non vérifié dans Twilio (compte d’essai)' : (j.message || `Twilio ${r.status}`) });
      return false;
    } catch (e) {
      errors.push({ to: n, message: 'Twilio injoignable' });
      return false;
    }
  }));

  return json(200, { sent: results.filter(Boolean).length, total: to.length, errors });
};
