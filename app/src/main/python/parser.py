import json, re, unicodedata

WORDS = {
    'one':1,'first':1,'two':2,'second':2,'three':3,'third':3,'four':4,'fourth':4,
    'five':5,'fifth':5,'six':6,'sixth':6,'seven':7,'seventh':7,'eight':8,'eighth':8,
    'nine':9,'ninth':9,'ten':10,'tenth':10,'eleven':11,'eleventh':11,'twelve':12,'twelfth':12
}
PREFIX = re.compile(r'^\s*(?:the\s+)?(?:correct\s+)?(?:answer|answers|option|options|choice|choices)(?:\s+is|\s+are)?\s*[:\-]?\s*', re.I)

def norm(s):
    return unicodedata.normalize('NFKC', str(s)).strip()

def clean_token(s):
    s = norm(s)
    s = re.sub(r'[`*_#]+', '', s).strip()
    s = PREFIX.sub('', s).strip()
    s = re.sub(r'^[\[(\{\s"\']+|[\])\}\s"\'.:;]+$', '', s).strip()
    return s

def option_value(v):
    if isinstance(v, bool) or v is None: return None
    if isinstance(v, int): return v if v > 0 else None
    s = clean_token(v).lower()
    if not s: return None
    if s in WORDS: return WORDS[s]
    if re.fullmatch(r'\d+', s):
        n = int(s); return n if n > 0 else None
    if re.fullmatch(r'[a-z]', s): return ord(s.upper()) - 64
    m = re.fullmatch(r'(?:option|choice)\s*([a-z]|\d+)', s, re.I)
    if m: return option_value(m.group(1))
    return None

def split_answers(value):
    if isinstance(value, list):
        vals = value
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        vals = [int(value)]
    else:
        s = norm(value)
        s = PREFIX.sub('', s).strip()
        s = re.sub(r'\b(?:and|or)\b|&|/|;|\n', ',', s, flags=re.I)
        vals = [x for x in re.split(r'\s*,\s*', s) if x.strip()]
    out=[]
    for x in vals:
        n=option_value(x)
        if n and n not in out: out.append(n)
    return out

def strip_fences(s):
    s=norm(s)
    s=re.sub(r'^\s*```(?:json)?\s*', '', s, flags=re.I)
    s=re.sub(r'\s*```\s*$', '', s)
    return s.strip()

def extract_json(s):
    s=strip_fences(s)
    try: return json.loads(s)
    except Exception: pass
    for op,cl in [('{','}'),('[',']')]:
        starts=[i for i,c in enumerate(s) if c==op]
        for st in starts:
            depth=0; ins=False; esc=False
            for i in range(st,len(s)):
                c=s[i]
                if ins:
                    if esc: esc=False
                    elif c=='\\': esc=True
                    elif c=='"': ins=False
                    continue
                if c=='"': ins=True
                elif c==op: depth+=1
                elif c==cl:
                    depth-=1
                    if depth==0:
                        try:return json.loads(s[st:i+1])
                        except Exception:break
    return None

def qid(v):
    if v is None:return None
    s=norm(v)
    s=re.sub(r'^\s*(?:question|q)\s*[:#.-]?\s*', '', s, flags=re.I).strip()
    if re.fullmatch(r'\d+(?:\s*\([A-Za-z0-9]+\)|[-.]?[ivxlcdm]+)?', s, re.I): return s.replace(' ','')
    return s[:40] if s and len(s)<=40 else None

def one_result(obj):
    if not isinstance(obj, dict): return None
    q = qid(obj.get('question_number', obj.get('question', obj.get('q'))))
    av = obj.get('answers', obj.get('answer', obj.get('correct_answers', obj.get('correct_answer'))))
    answers = split_answers(av) if av is not None else []
    txt = obj.get('answer_text', obj.get('text'))
    if txt is not None: txt=norm(txt)[:1000]
    multi = bool(obj.get('multiple_answers', len(answers)>1))
    return {'question_number':q,'answers':answers,'answer_text':txt,'multiple_answers':multi}

def parse_response(raw):
    raw=norm(raw)
    data=extract_json(raw)
    results=[]
    if isinstance(data, dict):
        seq=data.get('results') or data.get('questions')
        if isinstance(seq,list):
            results=[x for x in (one_result(o) for o in seq) if x]
        else:
            r=one_result(data)
            if r:results=[r]
    elif isinstance(data,list):
        if data and all(isinstance(x,dict) for x in data): results=[x for x in (one_result(o) for o in data) if x]
        elif data:
            answers=split_answers(data); results=[{'question_number':None,'answers':answers,'answer_text':None,'multiple_answers':len(answers)>1}]
    if not results:
        q=None
        qm=re.search(r'\b(?:Question|Q)\s*[:#.-]?\s*(\d+(?:\s*\([A-Za-z0-9]+\)|[-.]?[ivxlcdm]+)?)', raw, re.I)
        if qm:q=qid(qm.group(1))
        am=re.search(r'\b(?:correct\s+)?(?:answer|answers|option|options|choice|choices)\s*(?:is|are)?\s*[:\-]?\s*([^\n.]+)', raw, re.I)
        candidate=am.group(1) if am else raw
        answers=split_answers(candidate)
        if answers:
            results=[{'question_number':q,'answers':answers,'answer_text':None,'multiple_answers':len(answers)>1}]
        else:
            text=None
            if am:
                t=norm(am.group(1))
                if t and len(t)<=500:text=t
            results=[{'question_number':q,'answers':[],'answer_text':text,'multiple_answers':False}]
    status='parsed' if any(r['answers'] or r.get('answer_text') for r in results) else 'unparsed'
    return json.dumps({'status':status,'results':results}, ensure_ascii=False)
