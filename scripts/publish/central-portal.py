"""Publish a verified release bundle using the official Central Portal API."""

import base64
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def publish(action: str, bundle: Path, deployment_file: Path,
            coordinates: str) -> None:
    credential = json.load(sys.stdin)
    token = base64.b64encode((credential['username'] + ':' + credential['password']).encode()).decode()
    headers = {'Authorization': 'Bearer ' + token, 'User-Agent': 'RWML-Publisher/1.0.0'}
    group, artifact, version = coordinates.split(':')
    if group != 'io.github.rwpp-team' or artifact not in ('rwml-core', 'game-lib'):
        raise ValueError('Unexpected publication coordinates')

    def post(path: str, data: bytes = b'', content_type: str | None = None) -> str:
        request_headers = dict(headers)
        if content_type:
            request_headers['Content-Type'] = content_type
        request = urllib.request.Request('https://central.sonatype.com/api/v1/publisher/' + path,
                                         data=data, method='POST', headers=request_headers)
        for attempt in range(5):
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    return response.read().decode()
            except urllib.error.HTTPError as error:
                detail = error.read().decode(errors='replace')[:2000]
                for secret in (token, credential['password'], credential['username']):
                    detail = detail.replace(secret, '[redacted]')
                raise RuntimeError(f'Central HTTP {error.code}: {detail}') from None
            except urllib.error.URLError as error:
                trace = getattr(error.reason, '__traceback__', None)
                frames = []
                while trace:
                    frames.append(trace.tb_frame.f_code.co_name)
                    trace = trace.tb_next
                # Retrying an upload is safe only when TLS failed before sending HTTP data.
                if 'do_handshake' not in frames or attempt == 4:
                    raise
                print('TLS handshake interrupted; retrying before sending the request.', flush=True)
                time.sleep(min(2 ** attempt, 10))
        raise RuntimeError('Central connection unavailable')

    if deployment_file.exists():
        deployment = json.loads(deployment_file.read_text(encoding='utf-8-sig'))
        if deployment['coordinates'] != coordinates:
            raise ValueError('Saved deployment belongs to different coordinates')
        deployment_id = str(uuid.UUID(deployment['id']))
    elif action == 'Publish':
        boundary = 'RWML' + uuid.uuid4().hex
        payload = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="{bundle.name}"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n'
        ).encode() + bundle.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
        query = urllib.parse.urlencode({'publishingType': 'AUTOMATIC', 'name': artifact + '-' + version})
        deployment_id = str(uuid.UUID(post('upload?' + query, payload, 'multipart/form-data; boundary=' + boundary).strip()))
        deployment_file.write_text(json.dumps({'id': deployment_id, 'coordinates': coordinates,
                                               'publishingType': 'AUTOMATIC'}, indent=2), encoding='utf-8')
    else:
        raise ValueError('No saved deployment; publish the bundle first')

    print('Deployment:', deployment_id, flush=True)
    deadline = time.monotonic() + 600
    last_state = None
    while time.monotonic() < deadline:
        for attempt in range(5):
            try:
                status = json.loads(post('status?' + urllib.parse.urlencode({'id': deployment_id})))
                break
            except (urllib.error.URLError, TimeoutError):
                if attempt == 4:
                    raise
                print('Status connection interrupted; retrying the saved deployment.', flush=True)
                time.sleep(min(2 ** attempt, 10))
        state = status['deploymentState']
        if state != last_state:
            print('Central:', state, flush=True)
            last_state = state
        if state == 'FAILED':
            raise RuntimeError(json.dumps(status.get('errors', status), ensure_ascii=False))
        if state == 'PUBLISHED':
            print('Published', coordinates, flush=True)
            return
        if action == 'Status':
            return
        time.sleep(5)
    raise RuntimeError('Publishing is still pending. Run -Action Status to check again.')


if __name__ == '__main__':
    try:
        if sys.argv[1] not in ('Publish', 'Status'):
            raise ValueError('Expected Publish or Status')
        if len(sys.argv) != 5:
            raise ValueError('Expected action, bundle, deployment record, and coordinates')
        publish(sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]), sys.argv[4])
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
