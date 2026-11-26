import { useState, type FormEvent } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from './Auth';
import { ErrorNotice } from '../../components/Feedback';
import { demoZone } from '../../components/labels';
import type { ApiError } from '../../api/client';
import styles from '../../styles/Console.module.css';
export function LoginPage() {
  const auth = useAuth();
  const localHandoff = import.meta.env.VITE_LOCAL_HANDOFF === 'true';
  const [name, setName] = useState(''),
    [password, setPassword] = useState(''),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<ApiError | null>(null);
  if (auth.user) return <Navigate to={`/zones/${encodeURIComponent(demoZone)}`} replace />;
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || auth.loading) return;
    setBusy(true);
    setError(null);
    try {
      await auth.signIn(name, password);
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setPassword('');
      setBusy(false);
    }
  }
  return (
    <div className={styles.login}>
      <section className={styles.loginBrand}>
        <div className={styles.brand}>
          <span className={styles.brandMark}>
            P<span>·</span>
          </span>
          <span>
            현장 안내 관제<small>PARKING CONSOLE</small>
          </span>
        </div>
        <div>
          <span className={styles.eyebrow}>현장과 관리자를 연결합니다</span>
          <h1>
            관측부터 안내까지,
            <br />
            한곳에서 확인하세요.
          </h1>
          <p>
            구역의 마지막 상태와 사건 이력을
            <br />
            팀원들과 함께 확인하는 운영 공간입니다.
          </p>
        </div>
        <span className={styles.loginTag}>
          <span className={styles.demoDot} /> 가상 장치 데모
        </span>
      </section>
      <main className={styles.loginMain}>
        <form onSubmit={submit} className={styles.loginForm}>
          <span className={styles.eyebrow}>TEAM ACCESS</span>
          <h2>로그인</h2>
          <p className={styles.muted}>
            {localHandoff ? '로컬 공통 계정으로 접속하세요.' : '발급받은 팀원 계정으로 접속하세요.'}
          </p>
          {error && <ErrorNotice error={error} />}
          <label htmlFor="username">아이디</label>
          <input
            id="username"
            name="username"
            autoComplete="username"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            autoFocus
            disabled={busy}
          />
          <label htmlFor="password">비밀번호</label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            disabled={busy}
          />
          <button className={styles.primary} disabled={auth.loading || busy || !name || !password}>
            {auth.loading ? '계정 확인 중…' : busy ? '로그인 중…' : '로그인'}
          </button>
          <p className={styles.loginHelp}>
            {localHandoff ? (
              <>
                아이디: handoff-viewer
                <br />
                비밀번호: parking1234!
              </>
            ) : (
              <>
                계정이 없거나 접근이 제한된 경우
                <br />
                프로젝트 담당자에게 문의해 주세요.
              </>
            )}
          </p>
        </form>
      </main>
    </div>
  );
}
